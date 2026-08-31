import { app, BrowserWindow, dialog, Menu, shell } from "electron";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AgentSupervisor } from "./agent-supervisor.js";
import { ConfigStore } from "./config-store.js";
import { registerIpcHandlers } from "./ipc.js";
import { createDesktopPaths } from "./paths.js";
import { TrayController } from "./tray.js";

const moduleDirectory = path.dirname(fileURLToPath(import.meta.url));

const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
}

let mainWindow: BrowserWindow | null = null;
let tray: TrayController | null = null;
let supervisor: AgentSupervisor | null = null;
let configStore: ConfigStore | null = null;
let unsubscribeState: (() => void) | null = null;
let quitting = false;
let quitPromise: Promise<void> | null = null;
let startupSettings = true;

if (gotSingleInstanceLock) {
  app.on("second-instance", () => {
    showWindow();
  });

  app.whenReady().then(() => initialize()).catch((error: unknown) => {
    dialog.showErrorBox("Tailcat Mesh 启动失败", errorMessage(error));
    app.quit();
  });

  app.on("activate", () => showWindow());
  app.on("window-all-closed", () => {
    // Keep the tray process alive on Windows when the window is hidden/closed.
  });
  app.on("before-quit", (event) => {
    if (quitting || !supervisor) {
      return;
    }
    event.preventDefault();
    quitting = true;
    quitPromise = supervisor.stop()
      .then(() => undefined)
      .catch(() => undefined)
      .finally(() => app.quit());
  });
}

async function initialize(): Promise<void> {
  Menu.setApplicationMenu(null);
  const resourceRoot = app.isPackaged
    ? process.resourcesPath
    : path.resolve(moduleDirectory, "..", "..", "resources");
  const paths = app.isPackaged
    ? createDesktopPaths(resourceRoot)
    : createDesktopPaths(resourceRoot, resolveDevAgentJarPath(resourceRoot));
  configStore = new ConfigStore(app.getPath("userData"));
  const settings = await configStore.load();
  startupSettings = settings.launchAtStartup;
  applyLaunchAtStartup(startupSettings);

  supervisor = new AgentSupervisor(paths);
  registerIpcHandlers(
    supervisor,
    configStore,
    async () => {
      app.quit();
    },
    async (enabled) => {
      startupSettings = enabled;
      applyLaunchAtStartup(enabled);
    }
  );
  unsubscribeState = supervisor.onStateChange((state) => {
    tray?.update(state, startupSettings);
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("desktop:state-changed", state);
    }
  });
  tray = new TrayController(paths.trayIconPath, {
    open: showWindow,
    reconnect: () => runSupervisorAction(() => supervisor?.reconnect()),
    restart: () => runSupervisorAction(() => supervisor?.restart()),
    openLogs: () => runSupervisorAction(() => openLogs(paths.logPath)),
    setLaunchAtStartup: async (enabled) => {
      startupSettings = enabled;
      await configStore?.save({ launchAtStartup: enabled });
      applyLaunchAtStartup(enabled);
      tray?.update(supervisor?.getRuntimeState() ?? emptyState(), startupSettings);
    },
    quit: async () => app.quit()
  });
  tray.update(supervisor.getRuntimeState(), startupSettings);

  const enrolled = await supervisor.hasEnrollment();
  if (enrolled) {
    void supervisor.startExisting().catch((error: unknown) => {
      showStartupError(error);
    });
    if (!process.argv.includes("--hidden")) {
      createWindow();
    }
  } else {
    createWindow();
  }
}

function resolveDevAgentJarPath(resourceRoot: string): string {
  const repositoryJar = path.resolve(
    moduleDirectory,
    "..",
    "..",
    "..",
    "tailcat-mesh-agent",
    "target",
    "tailcat-mesh-agent-0.1.0-SNAPSHOT.jar"
  );
  return existsSync(repositoryJar)
    ? repositoryJar
    : path.join(resourceRoot, "agent", "tailcat-mesh-agent.jar");
}

function createWindow(): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.show();
    mainWindow.focus();
    return;
  }
  mainWindow = new BrowserWindow({
    width: 1080,
    height: 760,
    minWidth: 820,
    minHeight: 600,
    title: "Tailcat Mesh",
    titleBarStyle: "hidden",
    titleBarOverlay: {
      color: "#efede7",
      symbolColor: "#5a574f",
      height: 32
    },
    // Show the shell immediately so a renderer/preload issue cannot leave the
    // desktop invisible while the native window is already running.
    show: true,
    backgroundColor: "#f7f5ef",
    webPreferences: {
      preload: path.join(moduleDirectory, "..", "preload", "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  mainWindow.on("close", (event) => {
    if (!quitting) {
      event.preventDefault();
      mainWindow?.hide();
    }
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
  void mainWindow.loadFile(path.join(moduleDirectory, "..", "renderer", "index.html"));
  mainWindow.once("ready-to-show", () => mainWindow?.show());
}

function showWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) {
    mainWindow.restore();
  }
  mainWindow.show();
  mainWindow.focus();
}

function applyLaunchAtStartup(enabled: boolean): void {
  app.setLoginItemSettings({
    openAtLogin: enabled,
    path: process.execPath,
    args: ["--hidden"]
  });
}

async function runSupervisorAction(action: () => Promise<unknown> | undefined): Promise<void> {
  try {
    await action();
  } catch (error) {
    dialog.showErrorBox("Tailcat Mesh", errorMessage(error));
  }
}

async function openLogs(logPath: string): Promise<void> {
  const error = await shell.openPath(logPath);
  if (error) {
    throw new Error(error);
  }
}

function showStartupError(error: unknown): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
  }
  dialog.showErrorBox("Tailcat Mesh 无法启动 Agent", errorMessage(error));
}

function emptyState() {
  return {
    lifecycle: "stopped" as const,
    mode: null,
    enrolled: false,
    pid: null,
    exitCode: null,
    status: null,
    lastError: null,
    logTail: []
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
