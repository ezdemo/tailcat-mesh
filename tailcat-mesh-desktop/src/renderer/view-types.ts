import type { DesktopSettings } from "../shared/types.js";
import type { DesktopUiModel } from "../shared/ui-types.js";
import type { Translator } from "../shared/i18n.js";

export type AppRoute = "overview" | "networks" | "activity" | "logs" | "settings";
export type EnrollmentView = "form" | "connecting" | "success" | "error";
export type SettingsSection = "general" | "appearance" | "startup" | "advanced" | "about";
export type DialogName = "reset-device";

export interface RendererViewState {
  route: AppRoute;
  enrollmentView: EnrollmentView;
  connectionStage: number;
  connectionError: string | null;
  connectionDetails: string | null;
  showConnectionDetails: boolean;
  actionBusy: string | null;
  appError: string | null;
  selectedNetworkId: string | null;
  settingsSection: SettingsSection;
  logSearch: string;
  logLevel: string;
  logComponent: string;
  dialog: DialogName | null;
  toast: ToastState | null;
}

export interface ToastState {
  title: string;
  message: string;
  tone: "success" | "warning" | "danger" | "info";
}

export interface RenderContext {
  model: DesktopUiModel;
  settings: DesktopSettings;
  view: RendererViewState;
  mockMode: boolean;
  t: Translator;
}
