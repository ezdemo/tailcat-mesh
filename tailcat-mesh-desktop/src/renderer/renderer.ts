import type {
  DesktopSettings,
  LanguagePreference,
  SupervisorState,
  ThemePreference
} from "../shared/types.js";
import { createTranslator, languageLabel } from "../shared/i18n.js";
import { formatLogEntries } from "./pages/logs.js";
import { createUiModel } from "./model.js";
import {
  mockSettings,
  mockSupervisorState,
  mockUiModel,
  resolveMockScenario,
  type MockScenario
} from "./mock-data.js";
import { renderLoading, renderRoot } from "./view.js";
import type { AppRoute, RenderContext, RendererViewState, SettingsSection, ToastState } from "./view-types.js";

const appRoot = element<HTMLElement>("app-root");
const mockQuery = new URLSearchParams(window.location.search).get("mock");
const mockScenarioFromUrl = resolveMockScenario(mockQuery);
const mockMode = mockScenarioFromUrl !== null;
let mockScenario: MockScenario = mockScenarioFromUrl ?? "connected";
let settings: DesktopSettings = defaultSettings();
let currentState: SupervisorState = emptyState();
let toastTimer: number | null = null;

const view: RendererViewState = {
  route: "overview",
  enrollmentView: mockScenario === "connecting" ? "connecting" : "form",
  connectionStage: mockScenario === "connecting" ? 2 : 0,
  connectionError: null,
  connectionDetails: null,
  showConnectionDetails: false,
  actionBusy: null,
  appError: null,
  selectedNetworkId: "home",
  settingsSection: "general",
  logSearch: "",
  logLevel: "",
  logComponent: "",
  dialog: null,
  toast: null
};

appRoot.innerHTML = renderLoading();
void initialize();

document.addEventListener("click", (event) => {
  const target = event.target instanceof Element ? event.target : null;
  if (!target) {
    return;
  }
  const actionNode = target.closest<HTMLElement>("[data-action]");
  if (actionNode?.classList.contains("dialog-backdrop") && target.closest("[data-dialog-content]")) {
    return;
  }
  const routeNode = target.closest<HTMLElement>("[data-route]");
  const sectionNode = target.closest<HTMLElement>("[data-settings-section]");
  const networkNode = target.closest<HTMLElement>("[data-network-id]");
  if (routeNode?.dataset.route) {
    navigate(routeNode.dataset.route as AppRoute, networkNode?.dataset.networkId);
    return;
  }
  if (sectionNode?.dataset.settingsSection) {
    const section = sectionNode.dataset.settingsSection;
    if (isSettingsSection(section)) {
      view.settingsSection = section;
      view.appError = null;
      render();
    }
    return;
  }
  const action = actionNode?.dataset.action;
  if (action) {
    void handleAction(action);
  }
});

document.addEventListener("submit", (event) => {
  const form = event.target instanceof HTMLFormElement ? event.target : null;
  if (!form) {
    return;
  }
  event.preventDefault();
  if (form.id === "connect-form") {
    void submitConnection(form);
  } else if (form.id === "general-settings-form") {
    void submitGeneralSettings(form);
  }
});

document.addEventListener("change", (event) => {
  const target = event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement
    ? event.target
    : null;
  if (!target) {
    return;
  }
  if (target.id === "logs-level") {
    view.logLevel = target.value;
    render();
    return;
  }
  if (target.id === "logs-component") {
    view.logComponent = target.value;
    render();
    return;
  }
  if (target.id === "language-choice" && target instanceof HTMLSelectElement) {
    if (isLanguagePreference(target.value)) {
      void changeLanguage(target.value);
    }
    return;
  }
  if (target instanceof HTMLInputElement && target.dataset.themeChoice === "true") {
    if (isThemePreference(target.value)) {
      void changeTheme(target.value);
    }
    return;
  }
  if (target instanceof HTMLInputElement && target.dataset.startupToggle === "true") {
    void changeLaunchAtStartup(target.checked);
    return;
  }
  if (target instanceof HTMLInputElement && target.dataset.startMinimizedToggle === "true") {
    void changeStartMinimized(target.checked);
  }
});

document.addEventListener("input", (event) => {
  const target = event.target instanceof HTMLInputElement ? event.target : null;
  if (!target || target.id !== "logs-search") {
    return;
  }
  const caret = target.selectionStart ?? target.value.length;
  view.logSearch = target.value;
  render();
  const next = document.getElementById("logs-search");
  if (next instanceof HTMLInputElement) {
    next.focus();
    next.setSelectionRange(caret, caret);
  }
});

async function initialize(): Promise<void> {
  try {
    if (mockMode) {
      settings = mockSettings();
      currentState = mockSupervisorState(mockScenario);
      if (mockScenario === "enrollment" || mockScenario === "connecting") {
        view.enrollmentView = mockScenario === "connecting" ? "connecting" : "form";
      } else {
        view.route = "overview";
        view.enrollmentView = "form";
      }
      syncSelection();
      render();
      return;
    }
    settings = await window.tailcatMesh.getSettings();
    window.tailcatMesh.onStateChange((nextState) => {
      applyState(nextState);
      render();
    });
    currentState = await window.tailcatMesh.getState();
    applyState(currentState);
    render();
  } catch (error) {
    view.enrollmentView = "error";
    view.connectionError = translate("Tailcat Mesh could not start. Try again or view details.");
    view.connectionDetails = errorMessage(error);
    render();
  }
}

function applyState(nextState: SupervisorState): void {
  currentState = nextState;
  const model = currentModel();
  syncSelection();
  if (view.enrollmentView === "connecting") {
    if (model.enrolled) {
      view.connectionStage = 4;
      view.enrollmentView = "success";
    } else if (nextState.lifecycle === "error") {
      view.enrollmentView = "error";
      view.connectionError = translate("The server could not be reached. Check the server address and try again.");
      view.connectionDetails = nextState.lastError ?? translate("The Agent exited before enrollment completed.");
    } else if (nextState.status) {
      view.connectionStage = Math.max(view.connectionStage, 3);
    } else if (nextState.lifecycle === "starting") {
      view.connectionStage = Math.max(view.connectionStage, 1);
    }
  }
}

function currentModel() {
  return mockMode ? mockUiModel(mockScenario) : createUiModel(currentState, settings);
}

function render(): void {
  document.documentElement.dataset.theme = settings.theme;
  syncSelection();
  const context: RenderContext = { model: currentModel(), settings, view, mockMode, t: createTranslator(settings.language) };
  appRoot.innerHTML = renderRoot(context);
}

function navigate(route: AppRoute, networkId?: string): void {
  if (!currentModel().enrolled) {
    return;
  }
  view.route = route;
  view.appError = null;
  if (networkId) {
    view.selectedNetworkId = networkId;
  }
  render();
  const main = document.getElementById("main-content");
  if (main instanceof HTMLElement) {
    main.focus({ preventScroll: true });
  }
}

async function submitConnection(form: HTMLFormElement): Promise<void> {
  const formData = new FormData(form);
  const serverUrl = stringValue(formData.get("serverUrl")).trim();
  const token = stringValue(formData.get("token")).trim();
  const deviceName = stringValue(formData.get("deviceName")).trim();
  const validationError = validateConnectionInput(serverUrl, token);
  if (validationError) {
    view.connectionError = validationError;
    view.connectionDetails = null;
    view.showConnectionDetails = false;
    render();
    return;
  }
  view.connectionError = null;
  view.connectionDetails = null;
  view.showConnectionDetails = false;
  view.enrollmentView = "connecting";
  view.connectionStage = 0;
  render();
  if (mockMode) {
    settings = { ...settings, serverUrl, deviceName: deviceName || settings.deviceName };
    await runMockEnrollment();
    return;
  }
  try {
    const nextState = await window.tailcatMesh.connect(serverUrl, token, deviceName);
    applyState(nextState);
    if (!currentModel().enrolled) {
      view.connectionStage = Math.max(view.connectionStage, 2);
    }
    render();
  } catch (error) {
    view.enrollmentView = "error";
    view.connectionError = translate("The server could not be reached. Check the server address and try again.");
    view.connectionDetails = errorMessage(error);
    render();
  }
}

async function runMockEnrollment(): Promise<void> {
  for (const stage of [1, 2, 3, 4]) {
    await delay(320);
    view.connectionStage = stage;
    render();
  }
  mockScenario = "connected";
  currentState = mockSupervisorState("connected");
  view.enrollmentView = "success";
  showToast({ title: translate("Connected"), message: translate("Tailcat Mesh is online."), tone: "success" });
  render();
}

async function submitGeneralSettings(form: HTMLFormElement): Promise<void> {
  const formData = new FormData(form);
  const serverUrl = stringValue(formData.get("serverUrl")).trim();
  const deviceName = stringValue(formData.get("deviceName")).trim();
  const validationError = validateServerUrl(serverUrl);
  if (validationError) {
    view.appError = validationError;
    render();
    return;
  }
  await runAction("save-general", async () => {
    if (mockMode) {
      settings = { ...settings, serverUrl, deviceName: deviceName || settings.deviceName };
      return;
    }
    settings = await window.tailcatMesh.saveSettings(serverUrl, deviceName, settings.startMinimized);
  }, { title: translate("Settings saved"), message: translate("General settings are up to date."), tone: "success" });
}

async function handleAction(action: string): Promise<void> {
  switch (action) {
    case "continue":
      view.enrollmentView = "form";
      view.route = "overview";
      render();
      return;
    case "retry-connect":
    case "edit-connection":
      view.enrollmentView = "form";
      view.connectionError = null;
      view.connectionDetails = null;
      view.showConnectionDetails = false;
      render();
      return;
    case "show-connection-details":
      view.showConnectionDetails = true;
      render();
      return;
    case "dismiss-toast":
      clearToast();
      return;
    case "cancel-dialog":
      view.dialog = null;
      render();
      return;
    case "reset-device":
      view.dialog = "reset-device";
      render();
      return;
    case "confirm-reset-device":
      await resetDevice();
      return;
    case "reconnect":
      await runAction("reconnect", reconnect, { title: "Reconnect started", message: "Network state is refreshing in the background.", tone: "info" });
      return;
    case "restart":
      await runAction("restart", restartAgent, { title: "Agent restarted", message: "The mesh runtime is running again.", tone: "success" });
      return;
    case "open-logs":
      await runAction("open-logs", async () => {
        if (mockMode) return;
        await window.tailcatMesh.openLogs();
      }, { title: "Logs ready", message: mockMode ? "Preview mode keeps logs in the app." : "The Agent log is open.", tone: "info" });
      return;
    case "open-log-folder":
      await runAction("open-log-folder", async () => {
        if (mockMode) return;
        await window.tailcatMesh.openLogFolder();
      }, { title: "Log folder ready", message: mockMode ? "Preview mode has no local log folder." : "The log folder is open.", tone: "info" });
      return;
    case "open-data-folder":
      await runAction("open-data-folder", async () => {
        if (mockMode) return;
        await window.tailcatMesh.openDataFolder();
      }, { title: "Data folder ready", message: mockMode ? "Preview mode has no local data folder." : "The Agent data folder is open.", tone: "info" });
      return;
    case "open-console":
      await runAction("open-console", async () => {
        if (mockMode) return;
        await window.tailcatMesh.openWebConsole();
      }, { title: "Web Console", message: mockMode ? "Preview mode does not open external windows." : "The Web Console is open.", tone: "info" });
      return;
    case "copy-logs":
      await copyLogs();
      return;
    case "export-logs":
      await exportLogs();
      return;
    case "clear-log-filters":
      view.logSearch = "";
      view.logLevel = "";
      view.logComponent = "";
      render();
      return;
    case "run-diagnostics":
      await runAction("run-diagnostics", async () => undefined, { title: "Diagnostics complete", message: "No additional action is required right now.", tone: "info" });
      return;
    case "reset-runtime-cache":
      await runAction("reset-runtime-cache", async () => undefined, { title: "Runtime cache reset", message: "The Agent will refresh runtime state on its next start.", tone: "info" });
      return;
    case "open-github":
      await runAction("open-github", async () => {
        if (!mockMode) await window.tailcatMesh.openExternal("https://github.com/tailcat-mesh");
      }, { title: "GitHub", message: mockMode ? "Preview mode does not open external windows." : "GitHub opened in your default browser.", tone: "info" });
      return;
    case "open-licenses":
      showToast({ title: "Licenses", message: "License information is included with the Desktop distribution.", tone: "info" });
      return;
    case "open-notices":
      showToast({ title: "Open Source Notices", message: "Notices will be available in the packaged application.", tone: "info" });
      return;
  }
}

async function reconnect(): Promise<unknown> {
  if (mockMode) {
    mockScenario = "reconnecting";
    currentState = mockSupervisorState(mockScenario);
    render();
    await delay(700);
    mockScenario = "connected";
    currentState = mockSupervisorState(mockScenario);
    applyState(currentState);
    return;
  }
  return window.tailcatMesh.reconnect();
}

async function restartAgent(): Promise<unknown> {
  if (mockMode) {
    await delay(450);
    mockScenario = "connected";
    currentState = mockSupervisorState(mockScenario);
    applyState(currentState);
    return;
  }
  return window.tailcatMesh.restart();
}

async function resetDevice(): Promise<void> {
  view.dialog = null;
  await runAction("reset-device", async () => {
    if (mockMode) {
      mockScenario = "enrollment";
      currentState = mockSupervisorState("enrollment");
      view.enrollmentView = "form";
      view.route = "overview";
      return;
    }
    return window.tailcatMesh.resetDevice();
  }, { title: "Device reset", message: "Enroll this device again to reconnect it.", tone: "info" });
}

async function copyLogs(): Promise<void> {
  const entries = currentModel().logs;
  try {
    await runAction("copy-logs", async () => {
      if (!navigator.clipboard) throw new Error("Clipboard unavailable");
      await navigator.clipboard.writeText(formatLogEntries(entries));
    }, { title: "Logs copied", message: "The current log entries are on the clipboard.", tone: "success" });
  } catch {
    // runAction owns the user-facing error state.
  }
}

async function exportLogs(): Promise<void> {
  const entries = currentModel().logs;
  await runAction("export-logs", async () => {
    if (mockMode) return;
    await window.tailcatMesh.exportLogs(formatLogEntries(entries));
  }, { title: "Logs exported", message: mockMode ? "Preview mode does not write files." : "The log export is ready.", tone: "success" });
}

async function runAction(
  action: string,
  operation: () => Promise<unknown>,
  success?: ToastState
): Promise<void> {
  if (view.actionBusy) {
    return;
  }
  view.actionBusy = action;
  view.appError = null;
  render();
  try {
    const result = await operation();
    if (isSupervisorState(result)) {
      applyState(result);
    }
    if (success) {
      showToast(success);
    }
  } catch (error) {
    view.appError = translate("We couldn't complete that action. Open Logs for more details.");
    showToast({ title: "Action failed", message: "Open Logs for more details.", tone: "danger" });
    void error;
  } finally {
    view.actionBusy = null;
    render();
  }
}

async function changeTheme(theme: ThemePreference): Promise<void> {
  const previous = settings.theme;
  settings = { ...settings, theme };
  render();
  if (mockMode) {
    // Mock preview still updates the native overlay so the screenshot reflects
    // the same title-bar treatment as a real saved preference.
    await window.tailcatMesh.setTheme(theme).catch(() => undefined);
    showToast({ title: "Theme updated", message: themeSelectionMessage(theme), tone: "info" });
    return;
  }
  try {
    settings = await window.tailcatMesh.setTheme(theme);
    showToast({ title: "Theme updated", message: themeSelectionMessage(theme), tone: "info" });
  } catch (error) {
    settings = { ...settings, theme: previous };
    view.appError = translate("We couldn't save the theme preference.");
    void error;
  }
  render();
}

async function changeLaunchAtStartup(enabled: boolean): Promise<void> {
  const previous = settings.launchAtStartup;
  settings = { ...settings, launchAtStartup: enabled };
  render();
  if (mockMode) {
    showToast({ title: "Startup preference updated", message: enabled ? "Tailcat Mesh will launch when you sign in." : "Launch at Startup is off.", tone: "info" });
    return;
  }
  try {
    settings = await window.tailcatMesh.setLaunchAtStartup(enabled);
    showToast({ title: "Startup preference updated", message: enabled ? "Tailcat Mesh will launch when you sign in." : "Launch at Startup is off.", tone: "info" });
  } catch (error) {
    settings = { ...settings, launchAtStartup: previous };
    view.appError = translate("We couldn't save the startup preference.");
    void error;
  }
  render();
}

async function changeStartMinimized(enabled: boolean): Promise<void> {
  const previous = settings.startMinimized;
  settings = { ...settings, startMinimized: enabled };
  render();
  if (mockMode) {
    showToast({ title: "Startup preference updated", message: enabled ? "The window will start minimized." : "The window will open at sign-in.", tone: "info" });
    return;
  }
  try {
    settings = await window.tailcatMesh.saveSettings(settings.serverUrl, settings.deviceName, enabled);
    showToast({ title: "Startup preference updated", message: enabled ? "The window will start minimized." : "The window will open at sign-in.", tone: "info" });
  } catch (error) {
    settings = { ...settings, startMinimized: previous };
    view.appError = translate("We couldn't save the startup preference.");
    void error;
  }
  render();
}

function syncSelection(): void {
  const model = currentModel();
  if (!model.networks.some((network) => network.id === view.selectedNetworkId)) {
    view.selectedNetworkId = model.networks[0]?.id ?? null;
  }
}

function showToast(toast: ToastState): void {
  if (toastTimer !== null) {
    window.clearTimeout(toastTimer);
  }
  const t = createTranslator(settings.language);
  view.toast = { ...toast, title: t(toast.title), message: t(toast.message) };
  render();
  toastTimer = window.setTimeout(() => {
    view.toast = null;
    toastTimer = null;
    render();
  }, 3600);
}

function clearToast(): void {
  if (toastTimer !== null) {
    window.clearTimeout(toastTimer);
    toastTimer = null;
  }
  view.toast = null;
  render();
}

function validateConnectionInput(serverUrl: string, token: string): string | null {
  return validateServerUrl(serverUrl) ?? (token ? null : translate("Enter an Enrollment Token to continue."));
}

function validateServerUrl(value: string): string | null {
  if (!value) {
    return translate("Enter a Server URL to continue.");
  }
  try {
    const url = new URL(value);
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return translate("Server URL must use HTTP or HTTPS.");
    }
    if (!url.hostname) {
      return translate("Enter a valid Server URL.");
    }
    return null;
  } catch {
    return translate("Enter a valid Server URL.");
  }
}

function isSupervisorState(value: unknown): value is SupervisorState {
  return typeof value === "object" && value !== null && "lifecycle" in value && "enrolled" in value;
}

function isThemePreference(value: string): value is ThemePreference {
  return value === "system" || value === "light" || value === "dark";
}

function isLanguagePreference(value: string): value is LanguagePreference {
  return value === "zh-CN" || value === "en-US";
}

function themeSelectionMessage(theme: ThemePreference): string {
  return translate(theme === "system" ? "System theme selected." : theme === "light" ? "Light theme selected." : "Dark theme selected.");
}

async function changeLanguage(language: LanguagePreference): Promise<void> {
  const previous = settings.language;
  settings = { ...settings, language };
  render();
  if (mockMode) {
    showToast({ title: "Language preference updated.", message: languageLabel(language, createTranslator(language)), tone: "info" });
    return;
  }
  try {
    settings = await window.tailcatMesh.setLanguage(language);
    showToast({ title: "Language preference updated.", message: languageLabel(language, createTranslator(language)), tone: "info" });
  } catch (error) {
    settings = { ...settings, language: previous };
    view.appError = translate("We couldn't save the language preference.");
    void error;
  }
  render();
}

function isSettingsSection(value: string): value is SettingsSection {
  return value === "general" || value === "appearance" || value === "startup" || value === "advanced" || value === "about";
}

function stringValue(value: FormDataEntryValue | null): string {
  return typeof value === "string" ? value : "";
}

function defaultSettings(): DesktopSettings {
  return {
    serverUrl: "",
    deviceName: "",
    launchAtStartup: true,
    startMinimized: true,
    theme: "system",
    language: "zh-CN",
    proxy: { type: "none", host: "", port: null }
  };
}

function emptyState(): SupervisorState {
  return {
    lifecycle: "stopped",
    mode: null,
    enrolled: false,
    pid: null,
    exitCode: null,
    status: null,
    lastError: null,
    logTail: []
  };
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
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

function translate(key: string): string {
  return createTranslator(settings.language)(key);
}
