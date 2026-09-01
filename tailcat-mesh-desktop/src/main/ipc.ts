import { dialog, ipcMain, shell } from "electron";
import { writeFile } from "node:fs/promises";
import path from "node:path";
import type { DesktopApi, DesktopSettings, LanguagePreference, LocalProxySettings, ThemePreference } from "../shared/types.js";
import { AgentSupervisor } from "./agent-supervisor.js";
import { ConfigStore } from "./config-store.js";

export function registerIpcHandlers(
  supervisor: AgentSupervisor,
  configStore: ConfigStore,
  requestQuit: () => Promise<void>,
  setLaunchAtStartup: (enabled: boolean) => Promise<void>,
  setTheme: (theme: ThemePreference) => Promise<void>,
  setLanguage: (language: LanguagePreference) => Promise<void>
): void {
  ipcMain.handle("desktop:get-state", () => supervisor.getRuntimeState());
  ipcMain.handle("desktop:get-settings", () => configStore.load());
  ipcMain.handle("desktop:connect", async (_event, args: unknown) => {
    const input = asConnectInput(args);
    const settings = await configStore.save({
      serverUrl: input.serverUrl,
      ...(input.deviceName === undefined ? {} : { deviceName: input.deviceName }),
      ...(input.proxy === undefined ? {} : { proxy: input.proxy })
    });
    await supervisor.startFirstEnrollment(
      settings.serverUrl,
      input.token,
      settings.deviceName,
      settings.proxy
    );
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
  ipcMain.handle("desktop:reset-device", async () => supervisor.resetDevice());
  ipcMain.handle("desktop:save-settings", async (_event, args: unknown) => {
    const input = asGeneralSettings(args);
    const previous = await configStore.load();
    const settings = await configStore.save(input);
    if (supervisor.getRuntimeState().enrolled
      && (previous.serverUrl !== settings.serverUrl || previous.deviceName !== settings.deviceName)) {
      await supervisor.applySettings(settings);
    }
    return settings;
  });
  ipcMain.handle("desktop:set-theme", async (_event, value: unknown) => {
    const theme = asTheme(value);
    const settings = await configStore.save({ theme });
    await setTheme(theme);
    return settings;
  });
  ipcMain.handle("desktop:set-language", async (_event, value: unknown) => {
    const language = asLanguage(value);
    const settings = await configStore.save({ language });
    await setLanguage(language);
    return settings;
  });
  ipcMain.handle("desktop:set-launch-at-startup", async (_event, enabled: unknown) => {
    if (typeof enabled !== "boolean") {
      throw new Error("launchAtStartup must be boolean");
    }
    const settings = await configStore.save({ launchAtStartup: enabled });
    await setLaunchAtStartup(settings.launchAtStartup);
    return settings;
  });
  ipcMain.handle("desktop:set-proxy", async (_event, value: unknown) => {
    const settings = await configStore.save({ proxy: asProxySettings(value) });
    await supervisor.applySettings(settings);
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
  ipcMain.handle("desktop:open-log-folder", async () => {
    const error = await shell.openPath(path.dirname(supervisor.logPath()));
    if (error) {
      throw new Error(error);
    }
  });
  ipcMain.handle("desktop:open-data-folder", async () => {
    const error = await shell.openPath(supervisor.dataPath());
    if (error) {
      throw new Error(error);
    }
  });
  ipcMain.handle("desktop:export-logs", async (_event, value: unknown) => {
    if (typeof value !== "string") {
      throw new Error("Log export content is invalid");
    }
    const content = value.slice(0, 1_000_000);
    const result = await dialog.showSaveDialog({
      title: "Export Tailcat Mesh logs",
      defaultPath: path.join(path.dirname(supervisor.logPath()), "tailcat-mesh-log.txt"),
      filters: [{ name: "Text files", extensions: ["txt"] }]
    });
    if (result.canceled || !result.filePath) {
      return;
    }
    await writeFile(result.filePath, content, "utf8");
  });
  ipcMain.handle("desktop:open-external", async (_event, value: unknown) => {
    if (typeof value !== "string") {
      throw new Error("External URL is invalid");
    }
    const url = new URL(value);
    if (url.protocol !== "https:" && url.protocol !== "http:") {
      throw new Error("External URL must use HTTP or HTTPS");
    }
    await shell.openExternal(url.toString());
  });
  ipcMain.handle("desktop:quit", () => requestQuit());
}

function asConnectInput(value: unknown): {
  serverUrl: string;
  token: string;
  deviceName?: string;
  proxy?: LocalProxySettings;
} {
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
    ...(record.deviceName === undefined ? {} : { deviceName: record.deviceName }),
    ...(record.proxy === undefined ? {} : { proxy: asProxySettings(record.proxy) })
  };
}

function asGeneralSettings(value: unknown): {
  serverUrl: string;
  deviceName: string;
  startMinimized: boolean;
} {
  if (typeof value !== "object" || value === null) {
    throw new Error("Settings are invalid");
  }
  const record = value as Record<string, unknown>;
  if (typeof record.serverUrl !== "string" || typeof record.deviceName !== "string") {
    throw new Error("Server URL and Device Name are invalid");
  }
  if (typeof record.startMinimized !== "boolean") {
    throw new Error("Start minimized must be boolean");
  }
  return {
    serverUrl: record.serverUrl.trim(),
    deviceName: record.deviceName.trim(),
    startMinimized: record.startMinimized
  };
}

function asTheme(value: unknown): ThemePreference {
  if (value !== "system" && value !== "light" && value !== "dark") {
    throw new Error("Theme must be system, light, or dark");
  }
  return value;
}

function asLanguage(value: unknown): LanguagePreference {
  if (value !== "zh-CN" && value !== "en-US") {
    throw new Error("Language must be zh-CN or en-US");
  }
  return value;
}

function asProxySettings(value: unknown): LocalProxySettings {
  if (typeof value !== "object" || value === null) {
    throw new Error("Proxy settings are invalid");
  }
  const record = value as Record<string, unknown>;
  const rawType = typeof record.type === "string" ? record.type.trim().toLowerCase() : "";
  if (rawType === "none") {
    return { type: "none", host: "", port: null };
  }
  if (rawType !== "http" && rawType !== "socks5") {
    throw new Error("Proxy type must be HTTP or SOCKS5");
  }
  const type = rawType === "http" ? "http" : "socks5";
  const host = typeof record.host === "string" ? record.host.trim() : "";
  if (!host || /\s/.test(host)) {
    throw new Error("Proxy host is required");
  }
  const port = typeof record.port === "number"
    ? record.port
    : typeof record.port === "string" && record.port.trim() ? Number(record.port.trim()) : NaN;
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("Proxy port must be between 1 and 65535");
  }
  return { type, host, port };
}

// Keep this import-visible type close to the IPC boundary so accidental
// renderer access to Electron primitives remains impossible.
export type { DesktopApi, DesktopSettings };
