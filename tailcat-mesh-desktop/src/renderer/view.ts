import { escapeHtml } from "./components/html.js";
import { brandLogo } from "./components/brand.js";
import { icon } from "./components/icons.js";
import { agentLabel, connectionLabel, statusDot, toneForConnection } from "./components/status.js";
import { createTranslator, type Translator } from "../shared/i18n.js";
import { renderActivityPage } from "./pages/activity.js";
import { renderEnrollmentPage } from "./pages/enrollment.js";
import { renderLogsPage } from "./pages/logs.js";
import { renderNetworksPage } from "./pages/networks.js";
import { renderOverviewPage } from "./pages/overview.js";
import { renderSettingsPage } from "./pages/settings.js";
import type { AppRoute, RenderContext } from "./view-types.js";

export function renderRoot(context: RenderContext): string {
  const enrollmentFlow = !context.model.enrolled || context.view.enrollmentView !== "form";
  const application = enrollmentFlow ? renderEnrollmentShell(context) : renderDesktopShell(context);
  return `${application}${renderToast(context)}${renderDialog(context)}`;
}

export function renderLoading(t: Translator = createTranslator("zh-CN")): string {
  return `<div class="loading-frame"><header class="title-bar"><div class="window-brand">${logoMarkup()}<span>${t("Tailcat Mesh")}</span></div></header><main class="loading-content"><div class="loading-mark">${brandLogo(46)}</div><p class="eyebrow">${t("STARTING")}</p><h1>${t("Tailcat Mesh")}</h1><p>${t("Starting the desktop client…")}</p><div class="loading-skeletons"><span></span><span></span><span></span></div></main></div>`;
}

function renderEnrollmentShell(context: RenderContext): string {
  const { t } = context;
  return `<div class="enrollment-frame">${renderTitleBar(context)}<main class="enrollment-content">${renderEnrollmentPage(context)}</main><footer class="status-bar enrollment-status-bar"><span>${t("Not connected")}</span><span class="status-bar-separator">·</span><span>Tailcat Mesh ${t("Desktop")} v0.1.0</span></footer></div>`;
}

function renderDesktopShell(context: RenderContext): string {
  const mainClass = context.view.route === "logs" ? "main-content logs-main-content" : "main-content";
  return `<div class="app-frame">${renderTitleBar(context)}<div class="desktop-body">${renderSidebar(context)}<main class="${mainClass}" id="main-content" tabindex="-1">${renderPage(context)}</main></div>${renderStatusBar(context)}</div>`;
}

function renderTitleBar(context: RenderContext): string {
  const label = context.model.enrolled ? connectionLabel(context.model.connection, context.t) : context.t("Not connected");
  const tone = context.model.enrolled ? toneForConnection(context.model.connection) : "neutral";
  return `<header class="title-bar"><div class="window-brand">${logoMarkup()}<span>Tailcat Mesh</span></div><div class="title-bar-status">${statusDot(tone)}<span>${label}</span></div></header>`;
}

function renderSidebar(context: RenderContext): string {
  const { t } = context;
  const navItems: Array<{ route: AppRoute; label: string; icon: "gauge" | "network" | "activity" | "scroll-text" }> = [
    { route: "overview", label: t("Overview"), icon: "gauge" },
    { route: "networks", label: t("Networks"), icon: "network" },
    { route: "activity", label: t("Activity"), icon: "activity" },
    { route: "logs", label: t("Logs"), icon: "scroll-text" }
  ];
  return `<aside class="sidebar"><div class="sidebar-heading"><span class="sidebar-section-label">${t("WORKSPACE")}</span></div><nav class="sidebar-nav" aria-label="${t("Primary navigation")}">${navItems.map((item) => renderNavItem(item, context.view.route === item.route)).join("")}</nav><div class="sidebar-spacer"></div><nav class="sidebar-nav sidebar-settings" aria-label="${t("Settings navigation")}">${renderNavItem({ route: "settings", label: t("Settings"), icon: "settings" }, context.view.route === "settings")}</nav><div class="sidebar-footer"><span class="sidebar-footer-mark">${icon("shield-check", 14)}</span><span class="sidebar-footer-copy"><strong>${t("Mesh runtime")}</strong><small>${context.model.meshRuntime === "RUNNING" ? t("Running") : context.model.meshRuntime === "STARTING" ? t("Starting") : t("See status")}</small></span></div></aside>`;
}

function renderNavItem(item: { route: AppRoute; label: string; icon: "gauge" | "network" | "activity" | "scroll-text" | "settings" }, selected: boolean): string {
  return `<button type="button" class="nav-item${selected ? " selected" : ""}" data-route="${item.route}" aria-current="${selected ? "page" : "false"}" title="${item.label}">${icon(item.icon, 18)}<span>${item.label}</span></button>`;
}

function renderStatusBar(context: RenderContext): string {
  const { model, t } = context;
  const version = model.tailcatVersion ? `Tailcat v${escapeHtml(model.tailcatVersion)}` : t("Tailcat preparing");
  if (model.connection === "RECONNECTING") {
    return `<footer class="status-bar"><span class="status-bar-state status-warning">${statusDot("warning")} ${t("Reconnecting")}</span><span class="status-bar-separator">·</span><span>${t("Retrying control server connection…")}</span><span class="status-bar-spacer"></span><span>${version}</span></footer>`;
  }
  if (model.connection === "OFFLINE") {
    return `<footer class="status-bar"><span class="status-bar-state status-neutral">${statusDot("neutral")} ${t("Offline")}</span><span class="status-bar-separator">·</span><span>${t("Control Server")} ${t("Offline")} · ${t("Mesh Runtime")} ${model.meshRuntime === "RUNNING" ? t("Running") : t("Stopped")}</span><span class="status-bar-spacer"></span><span>${version}</span></footer>`;
  }
  if (model.connection === "ERROR") {
    return `<footer class="status-bar"><span class="status-bar-state status-danger">${statusDot("danger")} ${t("Error")}</span><span class="status-bar-separator">·</span><span>${t("Agent stopped unexpectedly")}</span><span class="status-bar-spacer"></span><span>${version}</span></footer>`;
  }
  return `<footer class="status-bar"><span class="status-bar-state status-success">${statusDot("success")} ${connectionLabel(model.connection, t)}</span><span class="status-bar-separator">·</span><span>${escapeHtml(model.serverUrl || t("Server not configured"))} · ${t("Agent")} ${agentLabel(model.agent, t)}</span><span class="status-bar-spacer"></span><span>${version}</span></footer>`;
}

function renderPage(context: RenderContext): string {
  switch (context.view.route) {
    case "overview": return renderOverviewPage(context);
    case "networks": return renderNetworksPage(context);
    case "activity": return renderActivityPage(context);
    case "logs": return renderLogsPage(context);
    case "settings": return renderSettingsPage(context);
  }
}

function renderToast(context: RenderContext): string {
  const toast = context.view.toast;
  if (!toast) {
    return "";
  }
  const iconName = toast.tone === "success" ? "check" : toast.tone === "warning" ? "alert-triangle" : toast.tone === "danger" ? "alert-triangle" : "info";
  return `<div id="toast-region" class="toast-region" aria-live="polite"><div class="toast toast-${toast.tone}" role="status">${icon(iconName, 17)}<div><strong>${escapeHtml(toast.title)}</strong><span>${escapeHtml(toast.message)}</span></div><button type="button" class="toast-close" aria-label="${escapeHtml(context.t("Dismiss notification"))}" data-action="dismiss-toast">${icon("x", 14)}</button></div></div>`;
}

function renderDialog(context: RenderContext): string {
  if (context.view.dialog !== "reset-device") {
    return "";
  }
  const { t } = context;
  return `<div class="dialog-backdrop" data-action="cancel-dialog"><section class="dialog" role="dialog" aria-modal="true" aria-labelledby="reset-dialog-title" data-dialog-content="true"><div class="dialog-icon dialog-icon-danger">${icon("alert-triangle", 20)}</div><h2 id="reset-dialog-title">${t("Reset this device?")}</h2><p>${t("This removes the local Tailcat Mesh identity. The device will need to be enrolled again.")}</p><div class="dialog-actions"><button type="button" class="button button-ghost" data-action="cancel-dialog">${t("Cancel")}</button><button type="button" class="button button-danger" data-action="confirm-reset-device">${t("Reset Device")}</button></div></section></div>`;
}

function logoMarkup(): string {
  return `<span class="logo-mark">${brandLogo(22)}</span>`;
}
