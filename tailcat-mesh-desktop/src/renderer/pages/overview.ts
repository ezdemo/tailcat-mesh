import { actionButton, escapeHtml } from "../components/html.js";
import { icon } from "../components/icons.js";
import {
  agentLabel,
  controlServerLabel,
  meshRuntimeLabel,
  networkLabel,
  pathLabel,
  statusBadge,
  toneForAgent,
  toneForConnection,
  toneForNetwork,
  toneForPath
} from "../components/status.js";
import type { UiNetwork, UiPeerPath } from "../../shared/ui-types.js";
import type { RenderContext } from "../view-types.js";

export function renderOverviewPage(context: RenderContext): string {
  const { model, view, t } = context;
  const busy = view.actionBusy;
  return `<div class="page-header">
    <div><p class="eyebrow">${t("WORKSPACE")}</p><h1>${t("Overview")}</h1><p class="page-subtitle">${t("A quick read on this device, its mesh networks, and runtime.")}</p></div>
    ${statusBadge(connectionLabel(model.connection, t), toneForConnection(model.connection), connectionIcon(model.connection))}
  </div>
  ${renderStateNotices(context)}
  ${view.appError ? `<div class="alert alert-danger" role="alert">${icon("alert-triangle", 16)}<span>${escapeHtml(view.appError)}</span></div>` : ""}
  <section class="panel connection-summary" aria-labelledby="connection-summary-title">
    <div class="connection-summary-main">
      <div class="device-icon">${icon("laptop", 22)}</div>
      <div><p class="section-label">${t("THIS DEVICE")}</p><h2 id="connection-summary-title">${escapeHtml(model.deviceName || t("This device"))}</h2><p class="summary-detail">${t("Connected to")} <span class="mono">${escapeHtml(model.serverUrl || "—")}</span></p></div>
    </div>
    <div class="connection-summary-actions">
      ${actionButton("reconnect", t("Reconnect"), icon("refresh", 16), { kind: "secondary", busy: busy === "reconnect", disabled: Boolean(busy && busy !== "reconnect") })}
      ${actionButton("restart", t("Restart Agent"), icon("loader", 16), { kind: "secondary", busy: busy === "restart", disabled: Boolean(busy && busy !== "restart") })}
      ${actionButton("open-logs", t("Open Logs"), icon("scroll-text", 16), { kind: "ghost", busy: busy === "open-logs", disabled: Boolean(busy && busy !== "open-logs") })}
    </div>
  </section>
  <div class="overview-grid">
    <section class="panel section-panel" aria-labelledby="overview-networks-title">
      <div class="panel-heading"><div><p class="section-label">${t("NETWORKS")}</p><h2 id="overview-networks-title">${t("Virtual networks")}</h2></div><span class="count-badge">${model.networks.length}</span></div>
      ${model.networks.length > 0 ? `<div class="data-table network-table" role="table" aria-label="${t("Virtual networks")}"><div class="table-header" role="row"><span>${t("Network")}</span><span>${t("Virtual IP")}</span><span>${t("Path")}</span><span>${t("Status")}</span></div>${model.networks.map((network) => renderNetworkRow(network, t)).join("")}</div>` : renderNoNetworks(context)}
      ${model.networks.length > 0 ? `<button class="link-button panel-link" type="button" data-route="networks">${t("View network details")} ${icon("chevron-right", 15)}</button>` : ""}
    </section>
    <section class="panel section-panel" aria-labelledby="runtime-title">
      <div class="panel-heading"><div><p class="section-label">${t("RUNTIME")}</p><h2 id="runtime-title">${t("System status")}</h2></div>${icon("server", 18, "panel-heading-icon")}</div>
      <div class="status-list">
        ${statusListRow(t("Agent"), agentLabel(model.agent, t), toneForAgent(model.agent), "laptop")}
        ${statusListRow(t("Mesh Runtime"), meshRuntimeLabel(model.meshRuntime, t), runtimeTone(model.meshRuntime), "network")}
        ${statusListRow(t("Control Server"), controlServerLabel(model.controlServer, t), model.controlServer === "CONNECTED" ? "success" : model.controlServer === "OFFLINE" ? "neutral" : "info", model.controlServer === "OFFLINE" ? "wifi-off" : "server")}
        ${statusListRow(t("Virtual LAN"), virtualLanLabel(model.virtualLan, t), virtualLanTone(model.virtualLan), "shield-check")}
        ${statusListRow("Tailcat", model.tailcatVersion ? `v${escapeHtml(model.tailcatVersion)}` : t("Preparing"), model.tailcatVersion ? "info" : "neutral", "terminal")}
      </div>
    </section>
  </div>`;
}

function renderStateNotices(context: RenderContext): string {
  const { model, view, t } = context;
  if (model.connection === "ERROR" || model.agent === "FAILED") {
    return `<section class="alert alert-danger state-notice" aria-labelledby="agent-error-title"><div class="alert-icon">${icon("alert-triangle", 18)}</div><div><strong id="agent-error-title">${t("Agent stopped unexpectedly")}</strong><span>${t("Tailcat Mesh will try to restart it automatically.")}</span></div><div class="alert-actions">${actionButton("restart", t("Restart now"), icon("refresh", 15), { kind: "secondary", busy: view.actionBusy === "restart" })}${actionButton("open-logs", t("View logs"), icon("scroll-text", 15), { kind: "ghost", busy: view.actionBusy === "open-logs" })}</div></section>`;
  }
  if (model.controlServer === "OFFLINE" && model.meshRuntime === "RUNNING") {
    return `<section class="alert alert-warning state-notice" aria-labelledby="control-offline-title"><div class="alert-icon">${icon("wifi-off", 18)}</div><div><strong id="control-offline-title">${t("Control Server Offline")}</strong><span>${t("Mesh Runtime Running. Existing network paths remain available while control connectivity is restored.")}</span></div><div class="alert-actions">${actionButton("reconnect", t("Reconnect"), icon("refresh", 15), { kind: "secondary", busy: view.actionBusy === "reconnect" })}</div></section>`;
  }
  if (model.connection === "RECONNECTING") {
    return `<section class="alert alert-warning state-notice" aria-live="polite"><div class="alert-icon">${icon("refresh", 18)}</div><div><strong>${t("Reconnecting")}</strong><span>${t("Retrying the control server connection. Mesh Runtime status is shown separately below.")}</span></div></section>`;
  }
  return "";
}

function renderNoNetworks(context: RenderContext): string {
  const { t } = context;
  return `<div class="empty-state compact-empty"><div class="empty-icon">${icon("network", 20)}</div><strong>${t("No mesh networks")}</strong><span>${t("This device is connected but hasn't been added to a virtual network yet.")}</span>${actionButton("open-console", t("Open Web Console"), icon("external-link", 15), { kind: "ghost" })}</div>`;
}

function renderNetworkRow(network: UiNetwork, t: RenderContext["t"]): string {
  return `<button class="network-row" type="button" data-network-id="${escapeHtml(network.id)}" data-route="networks" role="row"><span class="network-name-cell"><span class="network-avatar">${icon("network", 15)}</span><span><strong>${escapeHtml(network.name)}</strong><small>${escapeHtml(network.cidr)}</small></span></span><span class="mono">${escapeHtml(network.virtualIp)}</span><span>${statusBadge(pathLabel(network.path, t), toneForPath(network.path), pathIcon(network.path))}</span><span>${statusBadge(networkLabel(network.status, t), toneForNetwork(network.status), network.status === "ACTIVE" ? "check" : "circle-help")}</span></button>`;
}

function statusListRow(label: string, value: string, tone: "success" | "warning" | "danger" | "neutral" | "info", iconName: "laptop" | "network" | "wifi-off" | "server" | "shield-check" | "terminal"): string {
  return `<div class="status-list-row"><span class="status-list-label">${label}</span><span class="status-list-value">${statusBadge(value, tone, iconName)}</span></div>`;
}

function connectionLabel(value: "STARTING" | "CONNECTING" | "CONNECTED" | "RECONNECTING" | "OFFLINE" | "ERROR", t: RenderContext["t"]): string {
  return t({ STARTING: "Starting", CONNECTING: "Connecting", CONNECTED: "Connected", RECONNECTING: "Reconnecting", OFFLINE: "Offline", ERROR: "Error" }[value]);
}

function connectionIcon(value: "STARTING" | "CONNECTING" | "CONNECTED" | "RECONNECTING" | "OFFLINE" | "ERROR"): "wifi" | "wifi-off" | "refresh" | "alert-triangle" | "loader" {
  switch (value) {
    case "CONNECTED": return "wifi";
    case "RECONNECTING": return "refresh";
    case "OFFLINE": return "wifi-off";
    case "ERROR": return "alert-triangle";
    case "STARTING":
    case "CONNECTING": return "loader";
  }
}

function pathIcon(path: UiPeerPath): "wifi" | "globe" | "wifi-off" | "circle-help" {
  switch (path) {
    case "DIRECT": return "wifi";
    case "DERP": return "globe";
    case "OFFLINE": return "wifi-off";
    case "UNKNOWN": return "circle-help";
  }
}

function runtimeTone(value: "RUNNING" | "STARTING" | "STOPPED" | "ERROR" | "UNKNOWN"): "success" | "warning" | "danger" | "neutral" | "info" {
  switch (value) {
    case "RUNNING": return "success";
    case "STARTING": return "info";
    case "ERROR": return "danger";
    case "STOPPED": return "neutral";
    case "UNKNOWN": return "neutral";
  }
}

function virtualLanLabel(value: "READY" | "STARTING" | "STOPPED" | "ERROR" | "UNKNOWN", t: RenderContext["t"]): string {
  return t({ READY: "Ready", STARTING: "Starting", STOPPED: "Stopped", ERROR: "Error", UNKNOWN: "Unknown" }[value]);
}

function virtualLanTone(value: "READY" | "STARTING" | "STOPPED" | "ERROR" | "UNKNOWN"): "success" | "warning" | "danger" | "neutral" | "info" {
  switch (value) {
    case "READY": return "success";
    case "STARTING": return "info";
    case "ERROR": return "danger";
    case "STOPPED":
    case "UNKNOWN": return "neutral";
  }
}
