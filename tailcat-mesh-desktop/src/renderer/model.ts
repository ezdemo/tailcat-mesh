import type { DesktopSettings, LocalAgentStatus, LocalNetworkStatus, RuntimeLogEntry, SupervisorState } from "../shared/types.js";
import type {
  DesktopUiModel,
  UiAgentStatus,
  UiConnectionStatus,
  UiControlServerStatus,
  UiMeshRuntimeStatus,
  UiNetwork,
  UiNetworkStatus,
  UiPeerPath
} from "../shared/ui-types.js";

export function createUiModel(state: SupervisorState, settings: DesktopSettings): DesktopUiModel {
  const rawStatus = state.status;
  const enrolled = state.enrolled || Boolean(rawStatus?.deviceId);
  if (!enrolled) {
    return emptyUiModel(settings);
  }
  const agent = mapAgentStatus(state, rawStatus);
  const controlServer = mapControlServerStatus(rawStatus?.controlPlaneStatus);
  const meshRuntime = mapMeshRuntimeStatus(rawStatus?.tailcatState, agent);
  const connection = mapConnectionStatus(state, rawStatus, agent, controlServer, meshRuntime);
  const networks = (rawStatus?.networks ?? []).map((network) => mapNetwork(network));
  return {
    enrolled: true,
    connection,
    agent,
    controlServer,
    meshRuntime,
    deviceName: rawStatus?.deviceName || settings.deviceName,
    serverUrl: rawStatus?.serverUrl || settings.serverUrl,
    virtualIp: networks[0]?.virtualIp ?? null,
    tailcatVersion: rawStatus?.tailcatVersion || "",
    virtualLan: mapVirtualLanStatus(meshRuntime),
    networks,
    activity: [],
    logs: (state.logTail ?? []).map((entry) => mapLogEntry(entry, settings.language)),
    lastError: rawStatus?.lastError ?? state.lastError
  };
}

function mapLogEntry(entry: RuntimeLogEntry, language: DesktopSettings["language"]): DesktopUiModel["logs"][number] {
  return {
    id: entry.id,
    time: formatLogTime(entry.timestamp, language),
    level: entry.level,
    component: entry.component,
    message: entry.message
  };
}

function formatLogTime(timestamp: string, language: DesktopSettings["language"]): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  const time = new Intl.DateTimeFormat(language, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(date);
  return `${time}.${String(date.getMilliseconds()).padStart(3, "0")}`;
}

function emptyUiModel(settings: DesktopSettings): DesktopUiModel {
  return {
    enrolled: false,
    connection: "STARTING",
    agent: "STOPPED",
    controlServer: "UNKNOWN",
    meshRuntime: "UNKNOWN",
    deviceName: settings.deviceName,
    serverUrl: settings.serverUrl,
    virtualIp: null,
    tailcatVersion: "",
    virtualLan: "UNKNOWN",
    networks: [],
    activity: [],
    logs: [],
    lastError: null
  };
}

function mapAgentStatus(state: SupervisorState, status: LocalAgentStatus | null): UiAgentStatus {
  switch (state.lifecycle) {
    case "starting": return "STARTING";
    case "stopping": return "RESTARTING";
    case "error": return "FAILED";
    case "stopped": return "STOPPED";
    case "running":
      switch (status?.agentState?.trim().toUpperCase()) {
        case "STARTING": return "STARTING";
        case "RESTARTING": return "RESTARTING";
        case "FAILED": return "FAILED";
        case "STOPPED": return "STOPPED";
        case "RUNNING":
        default: return "RUNNING";
      }
  }
}

function mapControlServerStatus(value: string | undefined): UiControlServerStatus {
  switch (value?.trim().toUpperCase()) {
    case "ONLINE":
    case "CONNECTED":
    case "READY": return "CONNECTED";
    case "OFFLINE":
    case "DISCONNECTED":
    case "UNREACHABLE":
    case "ERROR": return "OFFLINE";
    default: return "UNKNOWN";
  }
}

function mapMeshRuntimeStatus(value: string | undefined, agent: UiAgentStatus): UiMeshRuntimeStatus {
  switch (value?.trim().toUpperCase()) {
    case "RUNNING":
    case "READY":
    case "ACTIVE": return "RUNNING";
    case "STARTING":
    case "PREPARING": return "STARTING";
    case "STOPPED": return "STOPPED";
    case "FAILED":
    case "ERROR": return "ERROR";
    default: return agent === "RUNNING" ? "UNKNOWN" : "STOPPED";
  }
}

function mapConnectionStatus(
  state: SupervisorState,
  status: LocalAgentStatus | null,
  agent: UiAgentStatus,
  controlServer: UiControlServerStatus,
  meshRuntime: UiMeshRuntimeStatus
): UiConnectionStatus {
  switch (status?.status?.trim().toUpperCase()) {
    case "RECONNECTING": return "RECONNECTING";
    case "CONNECTING": return "CONNECTING";
    case "ERROR":
    case "FAILED": return "ERROR";
    default: break;
  }
  if (state.lifecycle === "starting") {
    return "STARTING";
  }
  if (agent === "FAILED" || agent === "STOPPED" || meshRuntime === "ERROR") {
    return "ERROR";
  }
  // Control-plane loss is represented independently. The mesh runtime and
  // network rows remain available when the data plane is still running.
  if (controlServer === "OFFLINE") {
    return "OFFLINE";
  }
  if (meshRuntime === "RUNNING" && controlServer === "CONNECTED") {
    return "CONNECTED";
  }
  return "RECONNECTING";
}

function mapNetwork(network: LocalNetworkStatus): UiNetwork {
  const status = mapNetworkStatus(network.status);
  return {
    id: network.networkId,
    name: network.name,
    cidr: network.cidr,
    virtualIp: network.virtualIpv4,
    status,
    path: mapPeerPath(network.path),
    members: [],
    lastError: network.lastError
  };
}

function mapNetworkStatus(value: string | undefined): UiNetworkStatus {
  switch (value?.trim().toUpperCase()) {
    case "STARTING":
    case "PENDING": return "STARTING";
    case "READY":
    case "ACTIVE":
    case "RUNNING": return "ACTIVE";
    case "DEGRADED": return "DEGRADED";
    case "OFFLINE":
    case "STOPPED": return "OFFLINE";
    case "ERROR":
    case "FAILED": return "ERROR";
    default: return "STARTING";
  }
}

function mapPeerPath(value: string | null): UiPeerPath {
  switch (value?.trim().toUpperCase()) {
    case "DIRECT": return "DIRECT";
    case "DERP": return "DERP";
    case "OFFLINE": return "OFFLINE";
    default: return "UNKNOWN";
  }
}

function mapVirtualLanStatus(runtime: UiMeshRuntimeStatus): DesktopUiModel["virtualLan"] {
  switch (runtime) {
    case "RUNNING": return "READY";
    case "STARTING": return "STARTING";
    case "ERROR": return "ERROR";
    case "STOPPED": return "STOPPED";
    case "UNKNOWN": return "UNKNOWN";
  }
}
