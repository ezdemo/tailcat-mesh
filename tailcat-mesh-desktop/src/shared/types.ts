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

export interface SupervisorState {
  lifecycle: LifecycleState;
  mode: "first-enrollment" | "existing" | null;
  enrolled: boolean;
  pid: number | null;
  exitCode: number | null;
  status: LocalAgentStatus | null;
  lastError: string | null;
  logTail: string[];
}

export interface DesktopSettings {
  serverUrl: string;
  deviceName: string;
  launchAtStartup: boolean;
}

export interface DesktopApi {
  getState(): Promise<SupervisorState>;
  getSettings(): Promise<DesktopSettings>;
  connect(serverUrl: string, token: string, deviceName?: string): Promise<SupervisorState>;
  reconnect(): Promise<SupervisorState>;
  restart(): Promise<SupervisorState>;
  stop(): Promise<SupervisorState>;
  setLaunchAtStartup(enabled: boolean): Promise<DesktopSettings>;
  openWebConsole(): Promise<void>;
  openLogs(): Promise<void>;
  quit(): Promise<void>;
  onStateChange(listener: (state: SupervisorState) => void): () => void;
}

declare global {
  interface Window {
    tailcatMesh: DesktopApi;
  }
}
