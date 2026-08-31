import { contextBridge, ipcRenderer } from "electron";
import type { DesktopApi, SupervisorState } from "../shared/types.js";

const api: DesktopApi = {
  getState: () => ipcRenderer.invoke("desktop:get-state"),
  getSettings: () => ipcRenderer.invoke("desktop:get-settings"),
  connect: (serverUrl, token, deviceName) => ipcRenderer.invoke("desktop:connect", {
    serverUrl,
    token,
    deviceName
  }),
  reconnect: () => ipcRenderer.invoke("desktop:reconnect"),
  restart: () => ipcRenderer.invoke("desktop:restart"),
  stop: () => ipcRenderer.invoke("desktop:stop"),
  setLaunchAtStartup: (enabled) => ipcRenderer.invoke("desktop:set-launch-at-startup", enabled),
  openWebConsole: () => ipcRenderer.invoke("desktop:open-console"),
  openLogs: () => ipcRenderer.invoke("desktop:open-logs"),
  quit: () => ipcRenderer.invoke("desktop:quit"),
  onStateChange: (listener) => {
    const callback = (_event: Electron.IpcRendererEvent, state: SupervisorState) => listener(state);
    ipcRenderer.on("desktop:state-changed", callback);
    return () => ipcRenderer.removeListener("desktop:state-changed", callback);
  }
};

contextBridge.exposeInMainWorld("tailcatMesh", api);
