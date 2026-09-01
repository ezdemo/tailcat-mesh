import { contextBridge, ipcRenderer } from "electron";
import type { DesktopApi, LanguagePreference, SupervisorState, ThemePreference } from "../shared/types.js";

const api: DesktopApi = {
  getState: () => ipcRenderer.invoke("desktop:get-state"),
  getSettings: () => ipcRenderer.invoke("desktop:get-settings"),
  connect: (serverUrl, token, deviceName, proxy) => ipcRenderer.invoke("desktop:connect", {
    serverUrl,
    token,
    deviceName,
    proxy
  }),
  reconnect: () => ipcRenderer.invoke("desktop:reconnect"),
  restart: () => ipcRenderer.invoke("desktop:restart"),
  stop: () => ipcRenderer.invoke("desktop:stop"),
  resetDevice: () => ipcRenderer.invoke("desktop:reset-device"),
  saveSettings: (serverUrl, deviceName, startMinimized) => ipcRenderer.invoke(
    "desktop:save-settings",
    { serverUrl, deviceName, startMinimized }
  ),
  setTheme: (theme: ThemePreference) => ipcRenderer.invoke("desktop:set-theme", theme),
  setLanguage: (language: LanguagePreference) => ipcRenderer.invoke("desktop:set-language", language),
  setLaunchAtStartup: (enabled) => ipcRenderer.invoke("desktop:set-launch-at-startup", enabled),
  setProxy: (proxy) => ipcRenderer.invoke("desktop:set-proxy", proxy),
  openWebConsole: () => ipcRenderer.invoke("desktop:open-console"),
  openLogs: () => ipcRenderer.invoke("desktop:open-logs"),
  openLogFolder: () => ipcRenderer.invoke("desktop:open-log-folder"),
  openDataFolder: () => ipcRenderer.invoke("desktop:open-data-folder"),
  exportLogs: (content) => ipcRenderer.invoke("desktop:export-logs", content),
  openExternal: (url) => ipcRenderer.invoke("desktop:open-external", url),
  quit: () => ipcRenderer.invoke("desktop:quit"),
  onStateChange: (listener) => {
    const callback = (_event: Electron.IpcRendererEvent, state: SupervisorState) => listener(state);
    ipcRenderer.on("desktop:state-changed", callback);
    return () => ipcRenderer.removeListener("desktop:state-changed", callback);
  }
};

contextBridge.exposeInMainWorld("tailcatMesh", api);
