import { actionButton, escapeHtml } from "../components/html.js";
import { icon } from "../components/icons.js";
import { networkLabel, pathLabel, statusBadge, toneForNetwork, toneForPath } from "../components/status.js";
import type { UiNetwork, UiPeer } from "../../shared/ui-types.js";
import type { RenderContext } from "../view-types.js";

export function renderNetworksPage(context: RenderContext): string {
  const { model, view, t } = context;
  const selected = model.networks.find((network) => network.id === view.selectedNetworkId) ?? model.networks[0] ?? null;
  return `<div class="page-header"><div><p class="eyebrow">${t("NETWORKS")}</p><h1>${t("Networks")}</h1><p class="page-subtitle">${t("View the virtual networks assigned to this device.")}</p></div>${actionButton("open-console", t("Manage in Web Console"), icon("external-link", 15), { kind: "secondary", busy: view.actionBusy === "open-console" })}</div>
    ${selected ? `<div class="master-detail"><aside class="master-list panel" aria-label="${t("Network list")}"><div class="master-list-heading"><span>${t("Networks")}</span><span class="count-badge">${model.networks.length}</span></div><div class="master-list-items">${model.networks.map((network) => renderNetworkListItem(network, selected.id, t)).join("")}</div></aside><section class="detail-panel panel" aria-labelledby="network-detail-title">${renderNetworkDetail(selected, context)}</section></div>` : renderEmptyNetworks(context)}`;
}

function renderNetworkListItem(network: UiNetwork, selectedId: string, t: RenderContext["t"]): string {
  const selected = network.id === selectedId;
  return `<button class="master-list-item${selected ? " selected" : ""}" type="button" data-network-id="${escapeHtml(network.id)}" data-route="networks" aria-current="${selected ? "page" : "false"}"><span class="network-list-icon">${icon("network", 16)}</span><span class="master-list-copy"><strong>${escapeHtml(network.name)}</strong><small>${escapeHtml(network.virtualIp)}</small></span><span class="list-chevron">${icon("chevron-right", 15)}</span></button>`;
}

function renderNetworkDetail(network: UiNetwork, context: RenderContext): string {
  const { t } = context;
  return `<div class="detail-heading"><div><p class="section-label">${t("NETWORK DETAIL")}</p><div class="detail-title-row"><h2 id="network-detail-title">${escapeHtml(network.name)}</h2>${statusBadge(networkLabel(network.status, t), toneForNetwork(network.status), network.status === "ACTIVE" ? "check" : "circle-help")}</div><p class="detail-subtitle mono">${escapeHtml(network.cidr)}</p></div><button class="icon-button" type="button" title="${t("Open network in Web Console")}" aria-label="${t("Open network in Web Console")}" data-action="open-console">${icon("external-link", 16)}</button></div>
    <div class="network-facts"><div><span class="fact-label">${t("This device")}</span><strong>${escapeHtml(network.virtualIp)}</strong></div><div><span class="fact-label">${t("Path")}</span>${statusBadge(pathLabel(network.path, t), toneForPath(network.path), network.path === "DERP" ? "globe" : network.path === "DIRECT" ? "wifi" : "circle-help")}</div><div><span class="fact-label">${t("Network status")}</span>${statusBadge(networkLabel(network.status, t), toneForNetwork(network.status), network.status === "ACTIVE" ? "check" : "circle-help")}</div></div>
    ${network.lastError ? `<div class="alert alert-danger detail-alert" role="alert">${icon("alert-triangle", 16)}<span>${escapeHtml(network.lastError)}</span></div>` : ""}
    <div class="detail-section"><div class="panel-heading"><div><p class="section-label">${t("MEMBERS")}</p><h3>${t("Members")}</h3></div><span class="muted-text">${network.members.length} ${network.members.length === 1 ? t("device") : t("devices")}</span></div>${network.members.length > 0 ? `<div class="data-table members-table" role="table" aria-label="${t("Network members")}"><div class="table-header" role="row"><span>${t("Device")}</span><span>${t("Virtual IP")}</span><span>${t("Path")}</span><span>${t("Status")}</span></div>${network.members.map((member) => renderMemberRow(member, t)).join("")}</div>` : `<div class="empty-state member-empty"><div class="empty-icon">${icon("laptop", 18)}</div><strong>${t("Member details are not available yet")}</strong><span>${t("The Agent will show members as the Server publishes them.")}</span></div>`}</div>
    <div class="detail-footer"><span class="muted-text">${t("Network membership is managed by your administrator.")}</span>${actionButton("open-console", t("Open Web Console"), icon("external-link", 15), { kind: "ghost", busy: context.view.actionBusy === "open-console" })}</div>`;
}

function renderMemberRow(member: UiPeer, t: RenderContext["t"]): string {
  return `<div class="member-row" role="row"><span class="member-device"><span class="member-avatar">${icon(member.isThisDevice ? "laptop" : "server", 15)}</span><span><strong>${escapeHtml(member.name)}</strong>${member.isThisDevice ? `<small>${t("This device")}</small>` : ""}</span></span><span class="mono">${escapeHtml(member.virtualIp)}</span><span>${statusBadge(pathLabel(member.path, t), toneForPath(member.path), member.path === "DERP" ? "globe" : member.path === "DIRECT" ? "wifi" : "circle-help")}</span><span>${statusBadge(networkLabel(member.status, t), toneForNetwork(member.status), member.status === "ACTIVE" ? "check" : "circle-help")}</span></div>`;
}

function renderEmptyNetworks(context: RenderContext): string {
  const { t } = context;
  return `<section class="panel page-empty"><div class="empty-state"><div class="empty-icon">${icon("network", 22)}</div><h2>${t("No mesh networks")}</h2><span>${t("This device is connected but hasn't been added to a virtual network yet.")}</span>${actionButton("open-console", t("Open Web Console"), icon("external-link", 15), { kind: "primary", busy: context.view.actionBusy === "open-console" })}</div></section>`;
}
