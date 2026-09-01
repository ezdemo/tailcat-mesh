import type { DesktopSettings, LocalAgentStatus, RuntimeLogEntry, SupervisorState } from "../shared/types.js";
import type { DesktopUiModel, UiActivityEvent, UiLogEntry, UiNetwork } from "../shared/ui-types.js";

export type MockScenario = "connected" | "offline" | "reconnecting" | "error" | "empty" | "enrollment" | "connecting";

const mockServerUrl = "https://mesh.example.com";

const mockNetworks: UiNetwork[] = [
  {
    id: "home",
    name: "home",
    cidr: "10.77.0.0/24",
    virtualIp: "10.77.0.2",
    status: "ACTIVE",
    path: "DIRECT",
    lastError: null,
    members: [
      { id: "desktop-a", name: "DESKTOP-A", virtualIp: "10.77.0.2", path: "DIRECT", status: "ACTIVE", isThisDevice: true },
      { id: "nas-b", name: "NAS-B", virtualIp: "10.77.0.3", path: "DIRECT", status: "ACTIVE" },
      { id: "vps-c", name: "VPS-C", virtualIp: "10.77.0.4", path: "DERP", status: "ACTIVE" }
    ]
  },
  {
    id: "dev",
    name: "dev",
    cidr: "10.78.0.0/24",
    virtualIp: "10.78.0.4",
    status: "ACTIVE",
    path: "DERP",
    lastError: null,
    members: [
      { id: "desktop-a", name: "DESKTOP-A", virtualIp: "10.78.0.4", path: "DERP", status: "ACTIVE", isThisDevice: true },
      { id: "build-01", name: "BUILD-01", virtualIp: "10.78.0.7", path: "DIRECT", status: "ACTIVE" },
      { id: "staging", name: "STAGING", virtualIp: "10.78.0.8", path: "DERP", status: "ACTIVE" }
    ]
  }
];

const mockActivity: UiActivityEvent[] = [
  { id: "activity-1", group: "Today", time: "10:42", message: "Connected to mesh.example.com", tone: "success" },
  { id: "activity-2", group: "Today", time: "10:41", message: 'Virtual network "home" restored', tone: "success" },
  { id: "activity-3", group: "Today", time: "10:41", message: "Agent started", tone: "success" },
  { id: "activity-4", group: "Yesterday", time: "22:17", message: "Connection to NAS-B switched to Direct", tone: "success" },
  { id: "activity-5", group: "Yesterday", time: "22:15", message: "Server connection restored", tone: "success" }
];

const mockLogs: UiLogEntry[] = [
  { id: "log-1", time: "10:42:01", level: "INFO", component: "Agent", message: "Connected to control server" },
  { id: "log-2", time: "10:42:03", level: "INFO", component: "Tailcat", message: "Runtime started" },
  { id: "log-3", time: "10:42:05", level: "INFO", component: "VirtualLAN", message: "Network home restored" },
  { id: "log-4", time: "10:42:06", level: "WARN", component: "Peer", message: "DERP fallback in use for VPS-C" },
  { id: "log-5", time: "10:41:58", level: "DEBUG", component: "Agent", message: "Desired state revision 18 applied" },
  { id: "log-6", time: "10:41:42", level: "INFO", component: "Device", message: "Device identity loaded" }
];

export function resolveMockScenario(value: string | null): MockScenario | null {
  if (value === null) {
    return null;
  }
  switch (value.toLowerCase()) {
    case "0":
    case "enrollment": return "enrollment";
    case "connecting": return "connecting";
    case "offline": return "offline";
    case "reconnecting": return "reconnecting";
    case "error": return "error";
    case "empty": return "empty";
    case "1":
    case "connected":
    default: return "connected";
  }
}

export function mockSettings(): DesktopSettings {
  return {
    serverUrl: mockServerUrl,
    deviceName: "DESKTOP-A",
    launchAtStartup: true,
    startMinimized: true,
    theme: "light",
    language: "zh-CN",
    proxy: { type: "none", host: "", port: null }
  };
}

export function mockSupervisorState(scenario: MockScenario): SupervisorState {
  if (scenario === "enrollment" || scenario === "connecting") {
    return {
      lifecycle: scenario === "connecting" ? "starting" : "stopped",
      mode: scenario === "connecting" ? "first-enrollment" : null,
      enrolled: false,
      pid: null,
      exitCode: null,
      status: null,
      lastError: null,
      logTail: []
    };
  }
  const model = mockUiModel(scenario);
  const status: LocalAgentStatus = {
    status: scenario === "reconnecting" ? "RECONNECTING" : scenario === "error" ? "DEGRADED" : "ONLINE",
    controlPlaneStatus: model.controlServer,
    deviceId: "b8a8c95d-91df-4fec-9f15-27dc7b55e8d4",
    deviceName: model.deviceName,
    serverUrl: model.serverUrl,
    agentState: model.agent,
    pid: 18432,
    tailcatVersion: "0.3.0",
    tailcatState: model.meshRuntime,
    networks: model.networks.map((network) => ({
      networkId: network.id,
      name: network.name,
      cidr: network.cidr,
      virtualIpv4: network.virtualIp,
      status: network.status,
      path: network.path,
      lastError: network.lastError
    })),
    lastError: model.lastError,
    updatedAt: new Date().toISOString()
  };
  return {
    lifecycle: scenario === "error" ? "error" : "running",
    mode: "existing",
    enrolled: true,
    pid: status.pid,
    exitCode: scenario === "error" ? 1 : null,
    status,
    lastError: model.lastError,
    logTail: mockLogs.slice(0, 4).map<RuntimeLogEntry>((entry, index) => ({
      id: entry.id,
      timestamp: `2026-09-01T02:42:0${index + 1}.000Z`,
      level: entry.level,
      component: entry.component,
      source: "stdout",
      message: `stdout: ${entry.message}`
    }))
  };
}

export function mockUiModel(scenario: MockScenario): DesktopUiModel {
  const error = scenario === "error" ? "Agent stopped unexpectedly. Tailcat Mesh will try to restart it automatically." : null;
  const networks = scenario === "empty" ? [] : mockNetworks.map((network) => ({
    ...network,
    members: network.members.map((member) => ({ ...member }))
  }));
  return {
    enrolled: scenario !== "enrollment" && scenario !== "connecting",
    connection: scenario === "offline" ? "OFFLINE"
      : scenario === "reconnecting" ? "RECONNECTING"
        : scenario === "error" ? "ERROR" : "CONNECTED",
    agent: scenario === "error" ? "FAILED" : "RUNNING",
    controlServer: scenario === "offline" ? "OFFLINE" : "CONNECTED",
    meshRuntime: scenario === "error" ? "ERROR" : "RUNNING",
    deviceName: "DESKTOP-A",
    serverUrl: mockServerUrl,
    virtualIp: networks[0]?.virtualIp ?? null,
    tailcatVersion: "0.3.0",
    virtualLan: scenario === "error" ? "ERROR" : "READY",
    networks,
    activity: mockActivity,
    logs: mockLogs,
    lastError: error
  };
}
