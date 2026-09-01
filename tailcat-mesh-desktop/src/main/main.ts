import { app, BrowserWindow, dialog, Menu, nativeTheme, shell } from "electron";
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AgentSupervisor } from "./agent-supervisor.js";
import { ConfigStore } from "./config-store.js";
import { registerIpcHandlers } from "./ipc.js";
import { createDesktopPaths } from "./paths.js";
import { TrayController } from "./tray.js";
import type { LanguagePreference, ThemePreference } from "../shared/types.js";

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
let themePreference: ThemePreference = "system";
let languagePreference: LanguagePreference = "zh-CN";

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
  themePreference = settings.theme;
  languagePreference = settings.language;
  applyTheme(themePreference);
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
    },
    async (theme) => {
      themePreference = theme;
      applyTheme(theme);
    },
    async (language) => {
      languagePreference = language;
      tray?.update(supervisor?.getRuntimeState() ?? emptyState(), startupSettings, languagePreference);
    }
  );
  unsubscribeState = supervisor.onStateChange((state) => {
    tray?.update(state, startupSettings, languagePreference);
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
      tray?.update(supervisor?.getRuntimeState() ?? emptyState(), startupSettings, languagePreference);
    },
    quit: async () => app.quit()
  });
  tray.update(supervisor.getRuntimeState(), startupSettings, languagePreference);

  if (hasMockPreview()) {
    // The preview renderer owns its deterministic theme; keep native overlay
    // controls in sync with the light initial preview until a theme is chosen.
    themePreference = "light";
    applyTheme(themePreference);
    createWindow();
    return;
  }

  const enrolled = await supervisor.hasEnrollment();
  if (enrolled) {
    void supervisor.startExisting().catch((error: unknown) => {
      showStartupError(error);
    });
    if (!process.argv.includes("--hidden") || !settings.startMinimized) {
      createWindow();
    }
  } else {
    createWindow();
  }
}

function resolveDevAgentJarPath(resourceRoot: string): string {
  const repositoryTarget = path.resolve(
    moduleDirectory,
    "..",
    "..",
    "..",
    "tailcat-mesh-agent",
    "target"
  );
  try {
    const repositoryJar = readdirSync(repositoryTarget)
      .filter((name) => name.startsWith("tailcat-mesh-agent-")
        && name.endsWith(".jar")
        && !name.startsWith("original-"))
      .sort((left, right) => {
      const leftIsShaded = left.endsWith("-shaded.jar");
      const rightIsShaded = right.endsWith("-shaded.jar");
      if (leftIsShaded !== rightIsShaded) {
        // The shaded artifact is self-contained. A stale thin artifact can
        // start successfully and only fail later when a protocol type is
        // loaded during shutdown or runtime reporting.
        return leftIsShaded ? -1 : 1;
      }
        return right.localeCompare(left, undefined, { numeric: true });
      })[0];
    if (repositoryJar && existsSync(path.join(repositoryTarget, repositoryJar))) {
      return path.join(repositoryTarget, repositoryJar);
    }
  } catch {
    // Fall back to the staged resource when the Maven target does not exist.
  }
  return path.join(resourceRoot, "agent", "tailcat-mesh-agent.jar");
}

function createWindow(): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.show();
    mainWindow.focus();
    return;
  }
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 760,
    minWidth: 920,
    minHeight: 620,
    title: "Tailcat Mesh",
    icon: resolveLogoIconPath(),
    titleBarStyle: "hidden",
    titleBarOverlay: titleBarOverlayOptions(),
    // Wait until the first frame is ready so the shell never flashes blank.
    show: false,
    backgroundColor: isDarkTheme() ? "#111418" : "#F6F7F9",
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
  mainWindow.once("ready-to-show", () => mainWindow?.show());
  const mockScenario = process.argv.find((argument) => argument.startsWith("--mock-ui="))?.slice(10)
    ?? (process.argv.includes("--mock-ui") ? "1" : null);
  const filePath = path.join(moduleDirectory, "..", "renderer", "index.html");
  void mainWindow.loadFile(filePath, mockScenario ? { query: { mock: mockScenario } } : undefined);
}

function resolveLogoIconPath(): string {
  const resourceRoot = app.isPackaged
    ? process.resourcesPath
    : path.resolve(moduleDirectory, "..", "..", "resources");
  return path.join(resourceRoot, "tailcat-mesh-logo.png");
}

function hasMockPreview(): boolean {
  return process.argv.some((argument) => argument === "--mock-ui" || argument.startsWith("--mock-ui="));
}

function applyTheme(theme: ThemePreference): void {
  nativeTheme.themeSource = theme;
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.setTitleBarOverlay(titleBarOverlayOptions());
  }
}

function titleBarOverlayOptions(): Electron.TitleBarOverlayOptions {
  const dark = isDarkTheme();
  return {
    color: dark ? "#181C21" : "#FFFFFF",
    symbolColor: dark ? "#D8DEE7" : "#475467",
    height: 48
  };
}

function isDarkTheme(): boolean {
  return themePreference === "dark"
    || (themePreference === "system" && nativeTheme.shouldUseDarkColors);
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
