import { actionButton, escapeAttribute, escapeHtml } from "../components/html.js";
import { brandLogo } from "../components/brand.js";
import { icon } from "../components/icons.js";
import { agentLabel, statusBadge, toneForAgent } from "../components/status.js";
import type { RenderContext, SettingsSection } from "../view-types.js";

const sections: Array<{ id: SettingsSection; label: string; icon: "settings" | "sun" | "laptop" | "sliders" | "info" }> = [
  { id: "general", label: "General", icon: "settings" },
  { id: "appearance", label: "Appearance", icon: "sun" },
  { id: "startup", label: "Startup", icon: "laptop" },
  { id: "advanced", label: "Advanced", icon: "sliders" },
  { id: "about", label: "About", icon: "info" }
];

export function renderSettingsPage(context: RenderContext): string {
  const { t } = context;
  const section = context.view.settingsSection;
  const selectedSection = sections.find((item) => item.id === section);
  const selectedLabel = selectedSection ? t(selectedSection.label) : t("Settings");
  return `<div class="page-header"><div><p class="eyebrow">${t("PREFERENCES")}</p><h1>${t("Settings")}</h1><p class="page-subtitle">${t("Keep Tailcat Mesh focused on the essentials.")}</p></div></div>
    <div class="settings-layout"><nav class="settings-nav panel" aria-label="${t("Settings sections")}">${sections.map((item) => `<button type="button" class="settings-nav-item${item.id === section ? " selected" : ""}" data-settings-section="${item.id}" aria-current="${item.id === section ? "page" : "false"}">${icon(item.icon, 17)}<span>${t(item.label)}</span>${icon("chevron-right", 14)}</button>`).join("")}</nav><section class="panel settings-content" aria-labelledby="settings-section-title"><div class="settings-content-heading"><div><p class="section-label">${selectedLabel.toUpperCase()}</p><h2 id="settings-section-title">${selectedLabel}</h2></div></div>${renderSection(context, section)}</section></div>`;
}

function renderSection(context: RenderContext, section: SettingsSection): string {
  switch (section) {
    case "general": return renderGeneral(context);
    case "appearance": return renderAppearance(context);
    case "startup": return renderStartup(context);
    case "advanced": return renderAdvanced(context);
    case "about": return renderAbout(context);
  }
}

function renderGeneral(context: RenderContext): string {
  const { settings, view, t } = context;
  return `<form id="general-settings-form" class="settings-form">
    <div class="field-group"><label for="settings-server-url">${t("Server URL")}</label><input id="settings-server-url" name="serverUrl" type="url" value="${escapeAttribute(settings.serverUrl)}" placeholder="https://mesh.example.com" required /><p class="field-help">${t("Changing server requires reconnecting.")}</p></div>
    <div class="field-group"><div class="field-label-row"><label for="settings-device-name">${t("Device Name")}</label><span class="optional-label">${t("Shown to other members")}</span></div><input id="settings-device-name" name="deviceName" type="text" maxlength="255" value="${escapeAttribute(settings.deviceName)}" required /></div>
    ${view.appError ? `<div class="alert alert-danger" role="alert">${icon("alert-triangle", 16)}<span>${escapeHtml(view.appError)}</span></div>` : ""}
    <div class="form-actions"><button class="button button-primary" type="submit" data-action="save-general"${view.actionBusy === "save-general" ? " disabled" : ""}>${view.actionBusy === "save-general" ? `<span class="button-spinner"></span><span>${t("Saving…")}</span>` : `${icon("check", 15)}<span>${t("Save changes")}</span>`}</button></div>
  </form>`;
}

function renderAppearance(context: RenderContext): string {
  const { settings, t } = context;
  const theme = settings.theme;
  const language = settings.language;
  return `<div class="settings-copy"><p>${t("Choose how Tailcat Mesh should look. System follows your Windows appearance setting.")}</p></div><fieldset class="theme-options"><legend>${t("Theme")}</legend>${renderThemeOption("system", "System", "Use the Windows theme", "laptop", theme, t)}${renderThemeOption("light", "Light", "A bright, clear workspace", "sun", theme, t)}${renderThemeOption("dark", "Dark", "A darker workspace for low light", "moon", theme, t)}</fieldset><fieldset class="theme-options language-options"><legend>${t("Language")}</legend><div class="field-group"><label class="sr-only" for="language-choice">${t("Language")}</label><select id="language-choice" aria-label="${t("Language")}"><option value="zh-CN"${language === "zh-CN" ? " selected" : ""}>${t("Chinese (Simplified)")}</option><option value="en-US"${language === "en-US" ? " selected" : ""}>${t("English")}</option></select><p class="field-help">${t("Choose the language used by the desktop client.")}</p></div></fieldset>`;
}

function renderThemeOption(value: "system" | "light" | "dark", label: string, description: string, iconName: "laptop" | "sun" | "moon", selected: string, t: RenderContext["t"]): string {
  return `<label class="theme-option${selected === value ? " selected" : ""}"><input type="radio" name="theme-choice" value="${value}" data-theme-choice="true"${selected === value ? " checked" : ""} /><span class="theme-option-icon">${icon(iconName, 18)}</span><span class="theme-option-copy"><strong>${t(label)}</strong><small>${t(description)}</small></span><span class="radio-indicator"></span></label>`;
}

function renderStartup(context: RenderContext): string {
  const { settings, t } = context;
  return `<div class="settings-copy"><p>${t("Keep this device available without opening the full window every time you sign in.")}</p></div><div class="preference-list"><label class="preference-row"><span class="preference-copy"><strong>${t("Launch Tailcat Mesh when I sign in")}</strong><small>${t("Start the Agent automatically in the background.")}</small></span><input id="startup-toggle" type="checkbox" data-startup-toggle="true"${settings.launchAtStartup ? " checked" : ""} /><span class="toggle-track" aria-hidden="true"></span></label><label class="preference-row"><span class="preference-copy"><strong>${t("Start minimized")}</strong><small>${t("Keep the window out of the way while the tray icon stays available.")}</small></span><input id="start-minimized-toggle" type="checkbox" data-start-minimized-toggle="true"${settings.startMinimized ? " checked" : ""} /><span class="toggle-track" aria-hidden="true"></span></label></div>`;
}

function renderAdvanced(context: RenderContext): string {
  const { model, view, t } = context;
  return `<div class="settings-copy"><p>${t("Tools for troubleshooting this installation. Most users will not need these.")}</p></div><details class="advanced-disclosure"><summary><span><strong>${t("Advanced tools")}</strong><small>${t("Runtime and diagnostic actions")}</small></span>${icon("chevron-down", 16)}</summary><div class="advanced-actions">${actionButton("restart", t("Restart Agent"), icon("refresh", 15), { kind: "secondary", busy: view.actionBusy === "restart" })}${actionButton("reconnect", t("Reconnect"), icon("wifi", 15), { kind: "secondary", busy: view.actionBusy === "reconnect" })}${actionButton("open-data-folder", t("Open Data Folder"), icon("folder-open", 15), { kind: "ghost", busy: view.actionBusy === "open-data-folder" })}${actionButton("open-log-folder", t("Open Log Folder"), icon("scroll-text", 15), { kind: "ghost", busy: view.actionBusy === "open-log-folder" })}${actionButton("run-diagnostics", t("Run Diagnostics"), icon("clipboard", 15), { kind: "ghost", busy: view.actionBusy === "run-diagnostics" })}${actionButton("reset-runtime-cache", t("Reset Runtime Cache"), icon("refresh", 15), { kind: "ghost", busy: view.actionBusy === "reset-runtime-cache" })}</div></details><section class="danger-zone" aria-labelledby="danger-zone-title"><div><p class="section-label danger-label">${t("DANGER ZONE")}</p><h3 id="danger-zone-title">${t("Reset this device")}</h3><p>${t("Removes the local Tailcat Mesh identity. The device must be enrolled again.")}</p></div>${actionButton("reset-device", t("Reset Device"), icon("alert-triangle", 15), { kind: "danger", busy: view.actionBusy === "reset-device" })}</section><div class="agent-state-note">${statusBadge(`${t("Agent")} ${agentLabel(model.agent, t)}`, toneForAgent(model.agent), model.agent === "RUNNING" ? "check" : "circle-help")}</div>`;
}

function renderAbout(context: RenderContext): string {
  const { view, t } = context;
  return `<div class="about-brand"><div class="about-mark">${brandLogo(34)}</div><div><h3>Tailcat Mesh</h3><p>${t("Desktop client")}</p></div></div><dl class="version-list"><div><dt>${t("Desktop")}</dt><dd class="mono">0.1.0</dd></div><div><dt>${t("Agent")}</dt><dd class="mono">0.1.0</dd></div><div><dt>Tailcat</dt><dd class="mono">v${escapeHtml(context.model.tailcatVersion || "0.3.0")}</dd></div><div><dt>${t("Protocol")}</dt><dd class="mono">1</dd></div></dl><div class="about-links">${actionButton("open-github", t("GitHub"), icon("github", 15), { kind: "ghost", busy: view.actionBusy === "open-github" })}${actionButton("open-licenses", t("Licenses"), icon("book-open", 15), { kind: "ghost" })}${actionButton("open-notices", t("Open Source Notices"), icon("file-text", 15), { kind: "ghost" })}</div><p class="legal-copy">${t("Tailcat Mesh is an independent community project. It is not affiliated with or endorsed by Tailscale Inc.")}</p>`;
}
