import { Menu, Tray, nativeImage } from "electron";
import { Buffer } from "node:buffer";
import { deflateSync } from "node:zlib";
import { createTranslator } from "../shared/i18n.js";
import type { LanguagePreference, SupervisorState } from "../shared/types.js";

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
  private readonly icon: Electron.NativeImage;
  private launchAtStartup = true;
  private language: LanguagePreference = "zh-CN";

  public constructor(iconPath: string, actions: TrayActions) {
    this.actions = actions;
    // The project logo is a real PNG now, so Windows can use the same friendly
    // mark as the title bar and installer. Keep the generated raster icon as a
    // safe fallback for incomplete development/package resources.
    const image = nativeImage.createFromPath(iconPath);
    this.icon = image.isEmpty() ? createTrayIcon("neutral") : image;
    this.tray = new Tray(this.icon);
    this.tray.on("double-click", actions.open);
    this.tray.on("click", actions.open);
    this.tray.setToolTip("Tailcat Mesh");
  }

  public update(state: SupervisorState, launchAtStartup = this.launchAtStartup, language = this.language): void {
    this.launchAtStartup = launchAtStartup;
    this.language = language;
    const t = createTranslator(language);
    const trayState = resolveTrayState(state);
    // Keep the tray artwork consistent with the project logo. The current
    // path remains visible in the tooltip/menu, without turning a valid DERP
    // route or reconnecting state into an error-colored replacement icon.
    this.tray.setImage(this.icon);
    this.tray.setToolTip(`Tailcat Mesh — ${translateTrayLabel(trayState.label, t)}`);
    this.tray.setContextMenu(Menu.buildFromTemplate([
      { label: `Tailcat Mesh — ${translateTrayLabel(trayState.label, t)}`, enabled: false },
      { type: "separator" },
      { label: t("Open Tailcat Mesh"), click: this.actions.open },
      { label: t("Reconnect"), enabled: state.lifecycle === "running", click: () => void this.actions.reconnect() },
      { label: t("Restart Agent"), enabled: state.enrolled, click: () => void this.actions.restart() },
      { label: t("Open Logs"), click: () => void this.actions.openLogs() },
      { label: t("Launch at Startup"), type: "checkbox", checked: this.launchAtStartup,
        click: (item) => void this.actions.setLaunchAtStartup(item.checked) },
      { type: "separator" },
      { label: t("Quit"), click: () => void this.actions.quit() }
    ]));
  }

  public destroy(): void {
    this.tray.destroy();
  }
}

type TrayState = "connected" | "offline" | "reconnecting" | "error" | "neutral";

interface TrayStateInfo {
  state: TrayState;
  label: string;
}

function resolveTrayState(state: SupervisorState): TrayStateInfo {
  const control = state.status?.controlPlaneStatus?.trim().toUpperCase();
  const connection = state.status?.status?.trim().toUpperCase();
  if (state.lifecycle === "error" || connection === "ERROR" || connection === "FAILED") {
    return { state: "error", label: "Error" };
  }
  if (connection === "RECONNECTING") {
    return { state: "reconnecting", label: "Reconnecting" };
  }
  if (control === "OFFLINE" || control === "DISCONNECTED" || connection === "OFFLINE") {
    return { state: "offline", label: "Offline" };
  }
  if (connection === "CONNECTED" || connection === "ONLINE") {
    return { state: "connected", label: "Connected" };
  }
  return { state: "neutral", label: "Starting" };
}

function translateTrayLabel(label: string, t: (key: string) => string): string {
  return t(label);
}

function createTrayIcon(state: TrayState): Electron.NativeImage {
  const colors: Record<TrayState, Rgba> = {
    connected: [34, 160, 107, 255],
    offline: [152, 162, 179, 255],
    reconnecting: [230, 154, 23, 255],
    error: [217, 45, 32, 255],
    neutral: [123, 169, 255, 255]
  };
  const accent = colors[state];
  return nativeImage.createFromBuffer(createTrayPng(accent));
}

type Rgba = readonly [number, number, number, number];

const TRAY_ICON_SIZE = 32;
const TRAY_BACKGROUND: Rgba = [24, 28, 33, 255];
const TRAY_FOREGROUND: Rgba = [244, 246, 248, 255];

/**
 * Build a small, opaque-enough PNG in-process instead of asking Windows to
 * decode SVG. This keeps the tray icon reliable in both dev and packaged
 * Electron builds and avoids another runtime asset dependency.
 */
function createTrayPng(accent: Rgba): Buffer {
  const rowBytes = TRAY_ICON_SIZE * 4;
  const pixels = Buffer.alloc(TRAY_ICON_SIZE * rowBytes);

  fillRoundedRect(pixels, 2, 2, 30, 30, 8, TRAY_BACKGROUND);

  // Tailcat's compact mesh mark: a small network-shaped outline with two
  // nodes. It remains recognizable when Windows scales it down to 16px.
  drawLine(pixels, 8, 12, 24, 12, 2.1, accent);
  drawLine(pixels, 8, 12, 10, 23, 2.1, accent);
  drawLine(pixels, 10, 23, 22, 23, 2.1, accent);
  drawLine(pixels, 22, 23, 24, 12, 2.1, accent);
  drawLine(pixels, 12, 9, 12, 12, 2.1, accent);
  drawLine(pixels, 20, 9, 20, 12, 2.1, accent);
  fillCircle(pixels, 13, 16, 1.35, TRAY_FOREGROUND);
  fillCircle(pixels, 19, 16, 1.35, TRAY_FOREGROUND);

  // Keep the status dot separate from the mark so the path state is visible
  // without turning DERP/reconnecting into an error-looking icon.
  fillCircle(pixels, 25, 7, 3.2, TRAY_BACKGROUND);
  fillCircle(pixels, 25, 7, 2.25, accent);

  const scanlines = Buffer.alloc((rowBytes + 1) * TRAY_ICON_SIZE);
  for (let y = 0; y < TRAY_ICON_SIZE; y += 1) {
    const scanlineOffset = y * (rowBytes + 1);
    scanlines[scanlineOffset] = 0;
    pixels.copy(scanlines, scanlineOffset + 1, y * rowBytes, (y + 1) * rowBytes);
  }

  const header = Buffer.alloc(13);
  header.writeUInt32BE(TRAY_ICON_SIZE, 0);
  header.writeUInt32BE(TRAY_ICON_SIZE, 4);
  header[8] = 8; // RGBA channel depth
  header[9] = 6; // RGBA color type

  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(scanlines)),
    pngChunk("IEND", Buffer.alloc(0))
  ]);
}

function fillRoundedRect(
  pixels: Buffer,
  left: number,
  top: number,
  right: number,
  bottom: number,
  radius: number,
  color: Rgba
): void {
  for (let y = top; y <= bottom; y += 1) {
    for (let x = left; x <= right; x += 1) {
      const nearestX = Math.max(left + radius, Math.min(x, right - radius));
      const nearestY = Math.max(top + radius, Math.min(y, bottom - radius));
      const dx = x - nearestX;
      const dy = y - nearestY;
      if (dx * dx + dy * dy <= radius * radius) {
        setPixel(pixels, x, y, color);
      }
    }
  }
}

function fillCircle(pixels: Buffer, centerX: number, centerY: number, radius: number, color: Rgba): void {
  const minX = Math.floor(centerX - radius);
  const maxX = Math.ceil(centerX + radius);
  const minY = Math.floor(centerY - radius);
  const maxY = Math.ceil(centerY + radius);
  for (let y = minY; y <= maxY; y += 1) {
    for (let x = minX; x <= maxX; x += 1) {
      const dx = x - centerX;
      const dy = y - centerY;
      if (dx * dx + dy * dy <= radius * radius) {
        setPixel(pixels, x, y, color);
      }
    }
  }
}

function drawLine(
  pixels: Buffer,
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  width: number,
  color: Rgba
): void {
  const radius = width / 2;
  const minX = Math.floor(Math.min(startX, endX) - radius);
  const maxX = Math.ceil(Math.max(startX, endX) + radius);
  const minY = Math.floor(Math.min(startY, endY) - radius);
  const maxY = Math.ceil(Math.max(startY, endY) + radius);
  const deltaX = endX - startX;
  const deltaY = endY - startY;
  const lengthSquared = deltaX * deltaX + deltaY * deltaY;

  for (let y = minY; y <= maxY; y += 1) {
    for (let x = minX; x <= maxX; x += 1) {
      const projection = lengthSquared === 0
        ? 0
        : Math.max(0, Math.min(1, ((x - startX) * deltaX + (y - startY) * deltaY) / lengthSquared));
      const nearestX = startX + projection * deltaX;
      const nearestY = startY + projection * deltaY;
      const distanceX = x - nearestX;
      const distanceY = y - nearestY;
      if (distanceX * distanceX + distanceY * distanceY <= radius * radius) {
        setPixel(pixels, x, y, color);
      }
    }
  }
}

function setPixel(pixels: Buffer, x: number, y: number, color: Rgba): void {
  if (x < 0 || y < 0 || x >= TRAY_ICON_SIZE || y >= TRAY_ICON_SIZE) {
    return;
  }
  const offset = (y * TRAY_ICON_SIZE + x) * 4;
  pixels[offset] = color[0];
  pixels[offset + 1] = color[1];
  pixels[offset + 2] = color[2];
  pixels[offset + 3] = color[3];
}

function pngChunk(type: string, data: Buffer): Buffer {
  const typeBytes = Buffer.from(type, "ascii");
  const payload = Buffer.concat([typeBytes, data]);
  const length = Buffer.alloc(4);
  const checksum = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  checksum.writeUInt32BE(crc32(payload), 0);
  return Buffer.concat([length, payload, checksum]);
}

function crc32(data: Buffer): number {
  let checksum = 0xffffffff;
  for (const byte of data) {
    checksum ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      checksum = (checksum >>> 1) ^ ((checksum & 1) === 1 ? 0xedb88320 : 0);
    }
  }
  return (checksum ^ 0xffffffff) >>> 0;
}
