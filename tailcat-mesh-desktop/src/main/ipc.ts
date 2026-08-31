import { ipcMain, shell } from "electron";
import type { DesktopApi, DesktopSettings } from "../shared/types.js";
import { AgentSupervisor } from "./agent-supervisor.js";
import { ConfigStore } from "./config-store.js";

export function registerIpcHandlers(
  supervisor: AgentSupervisor,
  configStore: ConfigStore,
  requestQuit: () => Promise<void>,
  setLaunchAtStartup: (enabled: boolean) => Promise<void>
): void {
  ipcMain.handle("desktop:get-state", () => supervisor.getRuntimeState());
  ipcMain.handle("desktop:get-settings", () => configStore.load());
  ipcMain.handle("desktop:connect", async (_event, args: unknown) => {
    const input = asConnectInput(args);
    const settings = await configStore.save({
      serverUrl: input.serverUrl,
      ...(input.deviceName === undefined ? {} : { deviceName: input.deviceName })
    });
    await supervisor.startFirstEnrollment(settings.serverUrl, input.token, settings.deviceName);
    return supervisor.getRuntimeState();
  });
  ipcMain.handle("desktop:reconnect", async () => {
    await supervisor.reconnect();
    return supervisor.getRuntimeState();
  });
  ipcMain.handle("desktop:restart", async () => {
    await supervisor.restart();
    return supervisor.getRuntimeState();
  });
  ipcMain.handle("desktop:stop", async () => supervisor.stop());
  ipcMain.handle("desktop:set-launch-at-startup", async (_event, enabled: unknown) => {
    if (typeof enabled !== "boolean") {
      throw new Error("launchAtStartup must be boolean");
    }
    const settings = await configStore.save({ launchAtStartup: enabled });
    await setLaunchAtStartup(settings.launchAtStartup);
    return settings;
  });
  ipcMain.handle("desktop:open-console", async () => {
    const settings = await configStore.load();
    if (!settings.serverUrl) {
      throw new Error("Server URL is not configured");
    }
    await shell.openExternal(settings.serverUrl);
  });
  ipcMain.handle("desktop:open-logs", async () => {
    const error = await shell.openPath(supervisor.logPath());
    if (error) {
      throw new Error(error);
    }
  });
  ipcMain.handle("desktop:quit", () => requestQuit());
}

function asConnectInput(value: unknown): { serverUrl: string; token: string; deviceName?: string } {
  if (typeof value !== "object" || value === null) {
    throw new Error("Connect input is invalid");
  }
  const record = value as Record<string, unknown>;
  if (typeof record.serverUrl !== "string" || typeof record.token !== "string") {
    throw new Error("Server URL and Enrollment Token are required");
  }
  if (record.deviceName !== undefined && typeof record.deviceName !== "string") {
    throw new Error("Device Name is invalid");
  }
  return {
    serverUrl: record.serverUrl,
    token: record.token,
    ...(record.deviceName === undefined ? {} : { deviceName: record.deviceName })
  };
}

// Keep this import-visible type close to the IPC boundary so accidental
// renderer access to Electron primitives remains impossible.
export type { DesktopApi, DesktopSettings };
