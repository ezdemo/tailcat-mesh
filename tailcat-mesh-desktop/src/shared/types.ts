export type LifecycleState = "stopped" | "starting" | "running" | "stopping" | "error";

export interface LocalNetworkStatus {
  networkId: string;
  name: string;
  cidr: string;
  virtualIpv4: string;
  status: string;
  path: string | null;
  lastError: string | null;
}

export interface LocalAgentStatus {
  status: string;
  controlPlaneStatus: string;
  deviceId: string | null;
  deviceName: string;
  serverUrl: string;
  agentState: string;
  pid: number;
  tailcatVersion: string;
  tailcatState: string;
  networks: LocalNetworkStatus[];
  lastError: string | null;
  updatedAt: string;
}

export interface LocalStatusDescriptor {
  port: number;
  token: string;
  pid: number;
}

export type RuntimeLogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

export type RuntimeLogSource = "stdout" | "stderr" | "system";

export interface RuntimeLogEntry {
  id: string;
  timestamp: string;
  level: RuntimeLogLevel;
  component: string;
  source: RuntimeLogSource;
  message: string;
}

export interface SupervisorState {
  lifecycle: LifecycleState;
  mode: "first-enrollment" | "existing" | null;
  enrolled: boolean;
  pid: number | null;
  exitCode: number | null;
  status: LocalAgentStatus | null;
  lastError: string | null;
  logTail: RuntimeLogEntry[];
}

export type LocalProxyType = "none" | "http" | "socks5";

export interface LocalProxySettings {
  type: LocalProxyType;
  host: string;
  port: number | null;
}

export type ThemePreference = "system" | "light" | "dark";

export type LanguagePreference = "zh-CN" | "en-US";

export interface DesktopSettings {
  serverUrl: string;
  deviceName: string;
  launchAtStartup: boolean;
  startMinimized: boolean;
  theme: ThemePreference;
  language: LanguagePreference;
  proxy: LocalProxySettings;
}

export interface DesktopApi {
  getState(): Promise<SupervisorState>;
  getSettings(): Promise<DesktopSettings>;
  connect(
    serverUrl: string,
    token: string,
    deviceName?: string,
    proxy?: LocalProxySettings
  ): Promise<SupervisorState>;
  reconnect(): Promise<SupervisorState>;
  restart(): Promise<SupervisorState>;
  stop(): Promise<SupervisorState>;
  resetDevice(): Promise<SupervisorState>;
  saveSettings(serverUrl: string, deviceName: string, startMinimized: boolean): Promise<DesktopSettings>;
  setTheme(theme: ThemePreference): Promise<DesktopSettings>;
  setLanguage(language: LanguagePreference): Promise<DesktopSettings>;
  setLaunchAtStartup(enabled: boolean): Promise<DesktopSettings>;
  setProxy(proxy: LocalProxySettings): Promise<DesktopSettings>;
  openWebConsole(): Promise<void>;
  openLogs(): Promise<void>;
  openLogFolder(): Promise<void>;
  openDataFolder(): Promise<void>;
  exportLogs(content: string): Promise<void>;
  openExternal(url: string): Promise<void>;
  quit(): Promise<void>;
  onStateChange(listener: (state: SupervisorState) => void): () => void;
}

declare global {
  interface Window {
    tailcatMesh: DesktopApi;
  }
}
