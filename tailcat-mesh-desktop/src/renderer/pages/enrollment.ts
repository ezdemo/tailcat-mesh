import { actionButton, escapeAttribute, escapeHtml } from "../components/html.js";
import { brandLogo } from "../components/brand.js";
import { icon } from "../components/icons.js";
import type { RenderContext } from "../view-types.js";

export function renderEnrollmentPage(context: RenderContext): string {
  switch (context.view.enrollmentView) {
    case "connecting": return renderConnecting(context);
    case "success": return renderSuccess(context);
    case "error": return renderConnectionError(context);
    case "form": return renderConnectionForm(context);
  }
}

function renderConnectionForm(context: RenderContext): string {
  const { t } = context;
  const error = context.view.connectionError;
  const errorId = error ? "connection-error" : undefined;
  return `<section class="enrollment-card" aria-labelledby="enrollment-title">
    <div class="enrollment-brand-mark">${brandLogo(38)}</div>
    <p class="eyebrow">${t("FIRST CONNECTION")}</p>
    <h1 id="enrollment-title">${t("Connect this device to your mesh")}</h1>
    <p class="enrollment-intro">${t("Use the Server URL and enrollment token provided by your administrator.")}</p>
    <form id="connect-form" class="form-stack" novalidate>
      <div class="field-group">
        <label for="server-url">${t("Server URL")}</label>
        <input id="server-url" name="serverUrl" type="url" autocomplete="url" value="${escapeAttribute(context.settings.serverUrl)}" placeholder="https://mesh.example.com" required${errorId ? ` aria-describedby="${errorId}"` : ""} />
      </div>
      <div class="field-group">
        <label for="enrollment-token">${t("Enrollment Token")}</label>
        <input id="enrollment-token" name="token" type="password" autocomplete="one-time-code" placeholder="${t("Paste your enrollment token")}" required${errorId ? ` aria-describedby="${errorId}"` : ""} />
        <p class="field-help">${t("This token is used once and is not saved.")}</p>
      </div>
      <div class="field-group">
        <div class="field-label-row"><label for="device-name">${t("Device Name")}</label><span class="optional-label">${t("Optional")}</span></div>
        <input id="device-name" name="deviceName" type="text" autocomplete="off" maxlength="255" value="${escapeAttribute(context.settings.deviceName)}" placeholder="e.g. DESKTOP-A" />
      </div>
      ${error ? `<div id="${errorId}" class="alert alert-danger" role="alert">${icon("alert-triangle", 16)}<span>${escapeHtml(error)}</span></div>` : ""}
      <button id="connect-button" class="button button-primary button-wide" type="submit">
        <span>${t("Connect")}</span>${icon("chevron-right", 17)}
      </button>
    </form>
    <p class="enrollment-note">${icon("lock", 14)} <span>${t("Connection details stay on this device.")}</span></p>
  </section>`;
}

function renderConnecting(context: RenderContext): string {
  const { t } = context;
  const stage = Math.max(0, Math.min(context.view.connectionStage, 4));
  const steps = ["Server reachable", "Preparing runtime", "Preparing Tailcat", "Enrolling device", "Starting mesh"];
  return `<section class="enrollment-card enrollment-status-card" aria-labelledby="connecting-title" aria-live="polite">
    <div class="enrollment-brand-mark">${brandLogo(38)}</div>
    <p class="eyebrow">${t("PLEASE WAIT")}</p>
    <h1 id="connecting-title">${t("Connecting to Tailcat Mesh")}</h1>
    <p class="enrollment-intro">${t("Setting up this device. This can take a moment.")}</p>
    <ol class="connection-steps">
      ${steps.map((label, index) => renderStep(label, index, stage, t)).join("")}
    </ol>
    <div class="progress-track progress-${stage}" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round((stage / 4) * 100)}"><span></span></div>
  </section>`;
}

function renderStep(label: string, index: number, stage: number, t: RenderContext["t"]): string {
  const state = index < stage ? "complete" : index === stage ? "current" : "pending";
  const marker = state === "complete" ? icon("check", 14) : state === "current" ? '<span class="step-loader" aria-hidden="true"></span>' : `<span>${index + 1}</span>`;
  return `<li class="connection-step step-${state}"><span class="step-marker">${marker}</span><span>${t(label)}</span></li>`;
}

function renderSuccess(context: RenderContext): string {
  const { t } = context;
  return `<section class="enrollment-card enrollment-status-card" aria-labelledby="connected-title">
    <div class="success-mark">${icon("check", 26)}</div>
    <p class="eyebrow">${t("READY")}</p>
    <h1 id="connected-title">${t("Connected")}</h1>
    <p class="enrollment-intro">${t("Your device has joined Tailcat Mesh. You can now view networks and connection status from the desktop app.")}</p>
    ${actionButton("continue", t("Continue"), icon("chevron-right", 17), { kind: "primary", className: "button-wide" })}
  </section>`;
}

function renderConnectionError(context: RenderContext): string {
  const { t } = context;
  const details = context.view.connectionDetails;
  return `<section class="enrollment-card enrollment-status-card" aria-labelledby="connection-error-title">
    <div class="error-mark">${icon("alert-triangle", 23)}</div>
    <p class="eyebrow">${t("CONNECTION FAILED")}</p>
    <h1 id="connection-error-title">${t("Couldn’t connect")}</h1>
    <p class="enrollment-intro">${t("The server could not be reached. Check the server address and try again.")}</p>
    <div class="status-actions">
      ${actionButton("retry-connect", t("Retry"), icon("refresh", 16), { kind: "primary" })}
      ${actionButton("edit-connection", t("Edit details"), icon("settings", 16), { kind: "ghost" })}
    </div>
    ${details ? `<details class="technical-details" open><summary>${t("View details")}</summary><pre>${escapeHtml(details)}</pre></details>` : `<button type="button" class="text-button" data-action="show-connection-details">${t("View details")}</button>`}
  </section>`;
}
