import { actionButton, escapeHtml, option } from "../components/html.js";
import { icon } from "../components/icons.js";
import { levelTone } from "../components/status.js";
import type { UiLogEntry } from "../../shared/ui-types.js";
import type { RenderContext } from "../view-types.js";

export function renderLogsPage(context: RenderContext): string {
  const { t } = context;
  const entries = filterLogs(context.model.logs, context.view.logSearch, context.view.logLevel, context.view.logComponent);
  const components = [...new Set(context.model.logs.map((entry) => entry.component))].sort();
  const hasActiveFilters = context.view.logSearch.trim().length > 0
    || context.view.logLevel.length > 0
    || context.view.logComponent.length > 0;
  const emptyState = entries.length > 0 ? `<div class="log-table-wrap"><div class="data-table log-table" role="table" aria-label="${t("Technical logs")}"><div class="table-header" role="row"><span>${t("Time")}</span><span>${t("Level")}</span><span>${t("Component")}</span><span>${t("Message")}</span></div>${entries.map((entry) => renderLogRow(entry, t)).join("")}</div></div>` : `<div class="empty-state page-empty-content"><div class="empty-icon">${icon(hasActiveFilters ? "search" : "scroll-text", 20)}</div><h2>${t(hasActiveFilters ? "No matching logs" : "No logs yet")}</h2><span>${t(hasActiveFilters ? "Try changing the search or filters." : "Agent and Desktop events will appear here.")}</span>${hasActiveFilters ? actionButton("clear-log-filters", t("Clear filters"), icon("x", 15), { kind: "secondary" }) : ""}</div>`;
  return `<div class="page-header"><div><p class="eyebrow">${t("TROUBLESHOOTING")}</p><h1>${t("Logs")}</h1><p class="page-subtitle">${t("Technical events from the Desktop and Agent runtime.")}</p></div>${actionButton("open-log-folder", t("Open Folder"), icon("folder-open", 15), { kind: "secondary", busy: context.view.actionBusy === "open-log-folder" })}</div>
    <section class="panel logs-panel">
      <div class="logs-toolbar">
        <label class="search-field" for="logs-search">${icon("search", 16)}<input id="logs-search" type="search" placeholder="${t("Search logs…")}" value="${escapeHtml(context.view.logSearch)}" aria-label="${t("Search logs")}" /></label>
        <label class="filter-field"><span class="sr-only">${t("Log level")}</span><select id="logs-level" aria-label="${t("Filter by level")}">${option("", t("All levels"), context.view.logLevel === "")}${option("DEBUG", t("Debug"), context.view.logLevel === "DEBUG")}${option("INFO", t("Info"), context.view.logLevel === "INFO")}${option("WARN", t("Warn"), context.view.logLevel === "WARN")}${option("ERROR", t("Error"), context.view.logLevel === "ERROR")}</select></label>
        <label class="filter-field"><span class="sr-only">${t("Log component")}</span><select id="logs-component" aria-label="${t("Filter by component")}">${option("", t("All components"), context.view.logComponent === "")}${components.map((component) => option(component, component, context.view.logComponent === component)).join("")}</select></label>
        <div class="logs-toolbar-actions">${actionButton("copy-logs", t("Copy"), icon("copy", 15), { kind: "ghost", busy: context.view.actionBusy === "copy-logs" })}${actionButton("export-logs", t("Export"), icon("download", 15), { kind: "ghost", busy: context.view.actionBusy === "export-logs" })}</div>
      </div>
      <div class="logs-results">${emptyState}</div>
      <p class="table-caption">${entries.length} ${entries.length === 1 ? t("entry") : t("entries")} ${t("shown · Stack traces stay collapsed by default.")}</p>
    </section>`;
}

function filterLogs(entries: UiLogEntry[], search: string, level: string, component: string): UiLogEntry[] {
  const normalizedSearch = search.trim().toLowerCase();
  return entries.filter((entry) => {
    const matchesSearch = normalizedSearch.length === 0
      || `${entry.component} ${entry.message}`.toLowerCase().includes(normalizedSearch);
    return matchesSearch && (level === "" || entry.level === level) && (component === "" || entry.component === component);
  });
}

function renderLogRow(entry: UiLogEntry, t: RenderContext["t"]): string {
  return `<div class="log-row" role="row"><span class="mono log-time">${escapeHtml(entry.time)}</span><span><span class="log-level level-${levelTone(entry.level)}">${escapeHtml(t(entry.level === "DEBUG" ? "Debug" : entry.level === "INFO" ? "Info" : entry.level === "WARN" ? "Warn" : "Error"))}</span></span><span class="log-component">${escapeHtml(entry.component)}</span><span class="log-message">${escapeHtml(entry.message)}</span></div>`;
}

export function formatLogEntries(entries: UiLogEntry[]): string {
  return ["TIME\tLEVEL\tCOMPONENT\tMESSAGE", ...entries.map((entry) => `${entry.time}\t${entry.level}\t${entry.component}\t${entry.message}`)].join("\n");
}
