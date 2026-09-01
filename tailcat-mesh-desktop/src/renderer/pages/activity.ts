import { escapeHtml } from "../components/html.js";
import { icon } from "../components/icons.js";
import type { UiActivityEvent } from "../../shared/ui-types.js";
import type { RenderContext } from "../view-types.js";

export function renderActivityPage(context: RenderContext): string {
  const { model, t } = context;
  const events = model.activity;
  const groups = ["Today", "Yesterday"] as const;
  return `<div class="page-header"><div><p class="eyebrow">${t("ACTIVITY")}</p><h1>${t("Activity")}</h1><p class="page-subtitle">${t("A human-readable history of connection and network changes.")}</p></div></div>
    <section class="panel activity-panel">${events.length > 0 ? groups.map((group) => renderActivityGroup(group, events.filter((event) => event.group === group), t)).join("") : `<div class="empty-state page-empty-content"><div class="empty-icon">${icon("clock", 20)}</div><h2>${t("No activity yet")}</h2><span>${t("Connection events will appear here as this device starts working.")}</span></div>`}</section>`;
}

function renderActivityGroup(group: "Today" | "Yesterday", events: UiActivityEvent[], t: RenderContext["t"]): string {
  if (events.length === 0) {
    return "";
  }
  return `<section class="activity-group" aria-labelledby="activity-${group.toLowerCase()}"><h2 id="activity-${group.toLowerCase()}" class="activity-group-title">${t(group)}</h2><div class="timeline">${events.map((event) => renderActivityEvent(event, t)).join("")}</div></section>`;
}

function renderActivityEvent(event: UiActivityEvent, t: RenderContext["t"]): string {
  const iconName = event.tone === "warning" ? "alert-triangle" : event.tone === "danger" ? "alert-triangle" : "check";
  return `<div class="timeline-item"><time class="timeline-time">${escapeHtml(event.time)}</time><span class="timeline-marker marker-${event.tone}">${icon(iconName, 13)}</span><p>${escapeHtml(t(event.message))}</p></div>`;
}
