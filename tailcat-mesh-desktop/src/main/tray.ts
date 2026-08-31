import { Menu, Tray, nativeImage } from "electron";
import type { SupervisorState } from "../shared/types.js";

export interface TrayActions {
  open: () => void;
  reconnect: () => Promise<void>;
  restart: () => Promise<void>;
  openLogs: () => Promise<void>;
  setLaunchAtStartup: (enabled: boolean) => Promise<void>;
  quit: () => Promise<void>;
}

export class TrayController {
  private readonly tray: Tray;
  private readonly actions: TrayActions;
  private launchAtStartup = true;

  public constructor(iconPath: string, actions: TrayActions) {
    this.actions = actions;
    const image = nativeImage.createFromPath(iconPath);
    this.tray = new Tray(image.isEmpty() ? fallbackIcon() : image);
    this.tray.on("double-click", actions.open);
    this.tray.on("click", actions.open);
    this.tray.setToolTip("Tailcat Mesh");
  }

  public update(state: SupervisorState, launchAtStartup = this.launchAtStartup): void {
    this.launchAtStartup = launchAtStartup;
    const status = state.status?.status ?? state.lifecycle;
    const connected = status === "CONNECTED" || status === "ONLINE";
    this.tray.setToolTip(`Tailcat Mesh — ${displayStatus(status)}`);
    this.tray.setContextMenu(Menu.buildFromTemplate([
      { label: `Tailcat Mesh — ${connected ? "在线" : displayStatus(status)}`, enabled: false },
      { type: "separator" },
      { label: "打开工作台", click: this.actions.open },
      { label: "重新连接", enabled: state.lifecycle === "running", click: () => void this.actions.reconnect() },
      { label: "重启 Agent", enabled: state.enrolled, click: () => void this.actions.restart() },
      { label: "打开运行日志", click: () => void this.actions.openLogs() },
      { label: "登录时自动启动", type: "checkbox", checked: this.launchAtStartup,
        click: (item) => void this.actions.setLaunchAtStartup(item.checked) },
      { type: "separator" },
      { label: "退出 Tailcat Mesh", click: () => void this.actions.quit() }
    ]));
  }

  public destroy(): void {
    this.tray.destroy();
  }
}

function displayStatus(value: string): string {
  const localized: Record<string, string> = {
    CONNECTED: "已连接",
    ONLINE: "在线",
    PENDING: "等待审批",
    STARTING: "正在启动",
    RUNNING: "运行中",
    STOPPED: "已停止",
    CONNECTING: "正在连接",
    DEGRADED: "连接异常",
    ERROR: "异常",
    FAILED: "失败"
  };
  return localized[value.trim().toUpperCase()] ?? value;
}

function fallbackIcon(): Electron.NativeImage {
  // A tiny valid PNG keeps the tray usable if an unpacked development build
  // cannot decode the SVG resource.
  return nativeImage.createFromDataURL(
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
  );
}
