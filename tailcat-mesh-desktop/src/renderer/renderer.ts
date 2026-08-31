import type { DesktopSettings, LocalNetworkStatus, SupervisorState } from "../shared/types.js";

const onboarding = element<HTMLElement>("onboarding");
const dashboard = element<HTMLElement>("dashboard");
const connectForm = element<HTMLFormElement>("connect-form");
const serverUrlInput = element<HTMLInputElement>("server-url");
const tokenInput = element<HTMLInputElement>("enrollment-token");
const deviceNameInput = element<HTMLInputElement>("device-name");
const connectButton = element<HTMLButtonElement>("connect-button");
const connectButtonLabel = element<HTMLElement>("connect-button-label");
const connectError = element<HTMLElement>("connect-error");
const runtimeError = element<HTMLElement>("runtime-error");
const startupToggle = element<HTMLInputElement>("startup-toggle");
const headerStatus = element<HTMLElement>("header-status");
const sidebarStatusText = element<HTMLElement>("sidebar-status-text");
const sidebarStatusDot = element<HTMLElement>("sidebar-status-dot");
const headerStatusDot = element<HTMLElement>("header-status-dot");
const connectionNav = element<HTMLAnchorElement>("connection-nav");
const dashboardNav = Array.from(document.querySelectorAll<HTMLAnchorElement>(".dashboard-nav"));
const statusPill = element<HTMLElement>("status-pill");
const statusText = element<HTMLElement>("status-text");
const deviceNameValue = element<HTMLElement>("device-name-value");
const deviceIdValue = element<HTMLElement>("device-id-value");
const serverUrlValue = element<HTMLElement>("server-url-value");
const controlStatusValue = element<HTMLElement>("control-status-value");
const agentStateValue = element<HTMLElement>("agent-state-value");
const agentPidValue = element<HTMLElement>("agent-pid-value");
const tailcatVersionValue = element<HTMLElement>("tailcat-version-value");
const tailcatStateValue = element<HTMLElement>("tailcat-state-value");
const networksList = element<HTMLElement>("networks-list");
const noNetworks = element<HTMLElement>("no-networks");
const networkCount = element<HTMLElement>("network-count");
const logTail = element<HTMLElement>("log-tail");
const reconnectButton = element<HTMLButtonElement>("reconnect-button");
const restartButton = element<HTMLButtonElement>("restart-button");
const stopButton = element<HTMLButtonElement>("stop-button");
const consoleButton = element<HTMLButtonElement>("console-button");
const logsButton = element<HTMLButtonElement>("logs-button");

let settings: DesktopSettings = {
  serverUrl: "",
  deviceName: "",
  launchAtStartup: true
};
let currentState: SupervisorState | null = null;
let busy = false;

void initialize();

async function initialize(): Promise<void> {
  try {
    settings = await window.tailcatMesh.getSettings();
    serverUrlInput.value = settings.serverUrl;
    deviceNameInput.value = settings.deviceName;
    startupToggle.checked = settings.launchAtStartup;
    currentState = await window.tailcatMesh.getState();
    render(currentState);
    window.tailcatMesh.onStateChange((state) => {
      currentState = state;
      render(state);
    });
  } catch (error) {
    onboarding.hidden = false;
    showError(connectError, errorMessage(error));
  }
}

connectForm.addEventListener("submit", (event) => {
  event.preventDefault();
  void connect();
});
reconnectButton.addEventListener("click", () => void runAction(() => window.tailcatMesh.reconnect(), reconnectButton));
restartButton.addEventListener("click", () => void runAction(() => window.tailcatMesh.restart(), restartButton));
stopButton.addEventListener("click", () => void runAction(() => window.tailcatMesh.stop(), stopButton));
consoleButton.addEventListener("click", () => void runAction(() => window.tailcatMesh.openWebConsole(), consoleButton));
logsButton.addEventListener("click", () => void runAction(() => window.tailcatMesh.openLogs(), logsButton));
startupToggle.addEventListener("change", () => {
  void window.tailcatMesh.setLaunchAtStartup(startupToggle.checked)
    .then((next) => { settings = next; })
    .catch((error: unknown) => {
      startupToggle.checked = settings.launchAtStartup;
      showError(runtimeError, errorMessage(error));
    });
});

async function connect(): Promise<void> {
  clearError(connectError);
  setBusy(true);
  try {
    currentState = await window.tailcatMesh.connect(
      serverUrlInput.value,
      tokenInput.value,
      deviceNameInput.value
    );
    tokenInput.value = "";
    render(currentState);
  } catch (error) {
    showError(connectError, errorMessage(error));
  } finally {
    setBusy(false);
  }
}

async function runAction(action: () => Promise<unknown>, button: HTMLButtonElement): Promise<void> {
  clearError(runtimeError);
  button.disabled = true;
  try {
    await action();
  } catch (error) {
    showError(runtimeError, errorMessage(error));
  } finally {
    button.disabled = false;
  }
}

function render(state: SupervisorState): void {
  const enrolled = state.enrolled || Boolean(state.status?.deviceId);
  onboarding.hidden = enrolled;
  dashboard.hidden = !enrolled;
  connectionNav.href = enrolled ? "#dashboard" : "#connection";
  for (const link of dashboardNav) {
    link.hidden = !enrolled;
  }
  const status = state.status;
  const label = status?.status ?? (enrolled ? state.lifecycle : "not connected");
  headerStatus.textContent = displayStatus(label);
  sidebarStatusText.textContent = displayStatus(label);
  const stateClass = statusClass(label);
  sidebarStatusDot.className = `status-dot status-dot-${stateClass}`;
  headerStatusDot.className = `header-status-dot status-dot-${stateClass}`;

  if (!enrolled) {
    connectButton.disabled = busy
      || (state.mode === "first-enrollment" && ["starting", "running"].includes(state.lifecycle));
    if (state.lastError) {
      showError(connectError, state.lastError);
    }
    return;
  }

  statusText.textContent = displayStatus(label);
  statusPill.className = `status-pill status-${statusClass(label)}`;
  deviceNameValue.textContent = status?.deviceName || settings.deviceName || "—";
  deviceIdValue.textContent = status?.deviceId ? `设备 ID ${status.deviceId}` : "身份待确认";
  serverUrlValue.textContent = status?.serverUrl || settings.serverUrl || "—";
  controlStatusValue.textContent = status?.controlPlaneStatus
    ? displayStatus(status.controlPlaneStatus)
    : "—";
  agentStateValue.textContent = state.lifecycle === "running" ? "运行中" : displayStatus(state.lifecycle);
  agentPidValue.textContent = state.pid ? `PID ${state.pid}` : "PID —";
  tailcatVersionValue.textContent = status?.tailcatVersion ? `v${status.tailcatVersion}` : "—";
  tailcatStateValue.textContent = status?.tailcatState
    ? displayStatus(status.tailcatState)
    : "准备中";
  networkCount.textContent = String(status?.networks.length ?? 0);
  renderNetworks(status?.networks ?? []);
  logTail.textContent = state.logTail.length > 0 ? state.logTail.join("\n") : "No output yet.";
  if (state.lastError) {
    showError(runtimeError, state.lastError);
  } else {
    clearError(runtimeError);
  }
  reconnectButton.disabled = state.lifecycle !== "running";
  restartButton.disabled = !state.enrolled || state.lifecycle === "stopping";
  stopButton.disabled = state.lifecycle === "stopped";
}

function renderNetworks(networks: LocalNetworkStatus[]): void {
  networksList.replaceChildren();
  noNetworks.hidden = networks.length > 0;
  for (const network of networks) {
    const card = document.createElement("article");
    card.className = "network-card";
    const name = document.createElement("div");
    name.className = "network-name";
    name.textContent = network.name;
    const ip = document.createElement("div");
    ip.className = "network-ip";
    ip.textContent = network.virtualIpv4;
    const state = document.createElement("div");
    state.className = `network-state state-${statusClass(network.status)}`;
    state.textContent = network.path ? `${displayStatus(network.status)} · ${network.path}` : displayStatus(network.status);
    card.append(name, ip, state);
    if (network.lastError) {
      const error = document.createElement("div");
      error.className = "network-error";
      error.textContent = network.lastError;
      card.append(error);
    }
    networksList.append(card);
  }
}

function setBusy(value: boolean): void {
  busy = value;
  connectButton.disabled = value;
  serverUrlInput.disabled = value;
  tokenInput.disabled = value;
  deviceNameInput.disabled = value;
  connectButtonLabel.textContent = value ? "正在连接…" : "连接设备";
}

function showError(target: HTMLElement, message: string): void {
  target.textContent = message;
  target.hidden = false;
}

function clearError(target: HTMLElement): void {
  target.textContent = "";
  target.hidden = true;
}

function statusClass(value: string): string {
  const normalized = value.toLowerCase();
  if (normalized === "stopped" || normalized === "not connected" || normalized === "unknown") {
    return "neutral";
  }
  if (normalized.includes("disconnect")
    || normalized.includes("degraded")
    || normalized.includes("offline")
    || normalized.includes("error")
    || normalized.includes("failed")
    || normalized.includes("disabled")) {
    return "bad";
  }
  if (normalized.includes("pending") || normalized.includes("start") || normalized.includes("connecting")) {
    return "pending";
  }
  if (normalized === "connected"
    || normalized === "online"
    || normalized === "ready"
    || normalized === "running"
    || normalized === "approved") {
    return "good";
  }
  return "neutral";
}

function displayStatus(value: string): string {
  const key = value.trim().toUpperCase().replaceAll(" ", "_");
  const localized: Record<string, string> = {
    CONNECTING: "正在连接",
    CONNECTED: "已连接",
    ONLINE: "在线",
    PENDING: "等待审批",
    STARTING: "正在启动",
    RUNNING: "运行中",
    STOPPED: "已停止",
    NOT_CONNECTED: "未连接",
    DISCONNECTED: "已断开",
    READY: "就绪",
    APPROVED: "已批准",
    DEGRADED: "连接异常",
    DISABLED: "已禁用",
    ERROR: "异常",
    FAILED: "失败",
    UNKNOWN: "未知"
  };
  return localized[key] ?? value.replaceAll("_", " ").toLowerCase();
}

function element<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) {
    throw new Error(`UI element not found: ${id}`);
  }
  return node as T;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
