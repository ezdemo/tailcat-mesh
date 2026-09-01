export type UiConnectionStatus =
  | "STARTING"
  | "CONNECTING"
  | "CONNECTED"
  | "RECONNECTING"
  | "OFFLINE"
  | "ERROR";

export type UiAgentStatus = "STOPPED" | "STARTING" | "RUNNING" | "RESTARTING" | "FAILED";

export type UiNetworkStatus = "STARTING" | "ACTIVE" | "DEGRADED" | "OFFLINE" | "ERROR";

export type UiPeerPath = "DIRECT" | "DERP" | "UNKNOWN" | "OFFLINE";

export type UiControlServerStatus = "CONNECTED" | "OFFLINE" | "UNKNOWN";

export type UiMeshRuntimeStatus = "RUNNING" | "STARTING" | "STOPPED" | "ERROR" | "UNKNOWN";

export type UiLogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

export interface UiPeer {
  id: string;
  name: string;
  virtualIp: string;
  path: UiPeerPath;
  status: UiNetworkStatus;
  isThisDevice?: boolean;
}

export interface UiNetwork {
  id: string;
  name: string;
  cidr: string;
  virtualIp: string;
  status: UiNetworkStatus;
  path: UiPeerPath;
  members: UiPeer[];
  lastError: string | null;
}

export interface UiActivityEvent {
  id: string;
  group: "Today" | "Yesterday";
  time: string;
  message: string;
  tone: "success" | "warning" | "danger" | "neutral";
}

export interface UiLogEntry {
  id: string;
  time: string;
  level: UiLogLevel;
  component: string;
  message: string;
}

export interface DesktopUiModel {
  enrolled: boolean;
  connection: UiConnectionStatus;
  agent: UiAgentStatus;
  controlServer: UiControlServerStatus;
  meshRuntime: UiMeshRuntimeStatus;
  deviceName: string;
  serverUrl: string;
  virtualIp: string | null;
  tailcatVersion: string;
  virtualLan: "READY" | "STARTING" | "STOPPED" | "ERROR" | "UNKNOWN";
  networks: UiNetwork[];
  activity: UiActivityEvent[];
  logs: UiLogEntry[];
  lastError: string | null;
}
