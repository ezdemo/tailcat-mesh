import type {
  UiAgentStatus,
  UiConnectionStatus,
  UiControlServerStatus,
  UiMeshRuntimeStatus,
  UiNetworkStatus,
  UiPeerPath
} from "../../shared/ui-types.js";
import type { Translator } from "../../shared/i18n.js";
import { icon, type IconName } from "./icons.js";

export type StatusTone = "success" | "warning" | "danger" | "neutral" | "info";

export function connectionLabel(status: UiConnectionStatus, t: Translator = identity): string {
  return t({
    STARTING: "Starting",
    CONNECTING: "Connecting",
    CONNECTED: "Connected",
    RECONNECTING: "Reconnecting",
    OFFLINE: "Offline",
    ERROR: "Error"
  }[status]);
}

export function agentLabel(status: UiAgentStatus, t: Translator = identity): string {
  return t({
    STOPPED: "Stopped",
    STARTING: "Starting",
    RUNNING: "Running",
    RESTARTING: "Restarting",
    FAILED: "Failed"
  }[status]);
}

export function networkLabel(status: UiNetworkStatus, t: Translator = identity): string {
  return t({
    STARTING: "Starting",
    ACTIVE: "Active",
    DEGRADED: "Degraded",
    OFFLINE: "Offline",
    ERROR: "Error"
  }[status]);
}

export function pathLabel(status: UiPeerPath, t: Translator = identity): string {
  return t({
    DIRECT: "Direct",
    DERP: "DERP",
    UNKNOWN: "Unknown",
    OFFLINE: "Offline"
  }[status]);
}

export function toneForConnection(status: UiConnectionStatus): StatusTone {
  switch (status) {
    case "CONNECTED": return "success";
    case "RECONNECTING": return "warning";
    case "OFFLINE": return "neutral";
    case "ERROR": return "danger";
    case "STARTING":
    case "CONNECTING": return "info";
  }
}

export function toneForAgent(status: UiAgentStatus): StatusTone {
  switch (status) {
    case "RUNNING": return "success";
    case "STARTING":
    case "RESTARTING": return "info";
    case "FAILED": return "danger";
    case "STOPPED": return "neutral";
  }
}

export function toneForNetwork(status: UiNetworkStatus): StatusTone {
  switch (status) {
    case "ACTIVE": return "success";
    case "STARTING": return "info";
    case "DEGRADED": return "warning";
    case "ERROR": return "danger";
    case "OFFLINE": return "neutral";
  }
}

export function toneForPath(status: UiPeerPath): StatusTone {
  switch (status) {
    case "DIRECT": return "success";
    case "DERP": return "warning";
    case "OFFLINE": return "neutral";
    case "UNKNOWN": return "neutral";
  }
}

export function controlServerLabel(status: UiControlServerStatus, t: Translator = identity): string {
  return t({ CONNECTED: "Connected", OFFLINE: "Offline", UNKNOWN: "Unknown" }[status]);
}

export function meshRuntimeLabel(status: UiMeshRuntimeStatus, t: Translator = identity): string {
  return t({ RUNNING: "Running", STARTING: "Starting", STOPPED: "Stopped", ERROR: "Error", UNKNOWN: "Unknown" }[status]);
}

export function statusBadge(label: string, tone: StatusTone, iconName: IconName = "circle-help"): string {
  return `<span class="status-badge status-${tone}">${icon(iconName, 14)}<span>${escapeHtml(label)}</span></span>`;
}

export function statusDot(tone: StatusTone): string {
  return `<span class="status-dot status-dot-${tone}" aria-hidden="true"></span>`;
}

export function levelTone(level: string): StatusTone {
  switch (level) {
    case "ERROR": return "danger";
    case "WARN": return "warning";
    case "INFO": return "info";
    default: return "neutral";
  }
}

const identity: Translator = (key) => key;

function escapeHtml(value: string): string {
  return value.replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "'": "&#39;",
    '"': "&quot;"
  }[character] ?? character));
}
