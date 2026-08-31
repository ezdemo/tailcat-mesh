import { homedir, hostname } from "node:os";
import { readFileSync } from "node:fs";
import path from "node:path";
import type { DesktopSettings } from "../shared/types.js";

export interface DesktopPaths {
  meshHome: string;
  configDirectory: string;
  configPath: string;
  dataDirectory: string;
  logsDirectory: string;
  logPath: string;
  localStatusPath: string;
  resourceRoot: string;
  agentJarPath: string;
  trayIconPath: string;
}

export function createDesktopPaths(resourceRoot: string, agentJarPath?: string): DesktopPaths {
  const meshHome = path.join(homedir(), ".tailcat-mesh");
  const configDirectory = path.join(meshHome, "config");
  const dataDirectory = path.join(meshHome, "data", "agent");
  const logsDirectory = path.join(meshHome, "logs");
  return {
    meshHome,
    configDirectory,
    configPath: path.join(configDirectory, "agent.yml"),
    dataDirectory,
    logsDirectory,
    logPath: path.join(logsDirectory, "desktop-agent.log"),
    localStatusPath: path.join(dataDirectory, "local-status.json"),
    resourceRoot,
    agentJarPath: agentJarPath ?? path.join(resourceRoot, "agent", "tailcat-mesh-agent.jar"),
    trayIconPath: path.join(resourceRoot, "tray.svg")
  };
}

export function defaultDeviceName(): string {
  return hostname().trim() || "Tailcat Mesh Device";
}

export function resolveConfiguredDataDirectory(configPath: string, fallback: string): string {
  // The Agent's current schema is intentionally small. Reading this one
  // field lets Desktop upgrades continue to honor an existing custom dataDir
  // without parsing or rewriting the rest of the YAML document.
  let content: string;
  try {
    content = readFileSync(configPath, "utf8");
  } catch {
    return path.resolve(fallback);
  }
  const match = content.match(/^\s+dataDir\s*:\s*(.+?)\s*(?:#.*)?$/m);
  if (!match?.[1]) {
    return path.resolve(fallback);
  }
  let configured = match[1].trim().replace(/^['"]|['"]$/g, "");
  if (configured === "~") {
    return homedir();
  }
  if (configured.startsWith("~/") || configured.startsWith("~\\")) {
    configured = path.join(homedir(), configured.slice(2));
  }
  return path.resolve(path.isAbsolute(configured)
    ? configured
    : path.join(path.dirname(configPath), configured));
}

export function normalizedSettings(settings: Partial<DesktopSettings>): DesktopSettings {
  const serverUrl = typeof settings.serverUrl === "string" ? settings.serverUrl.trim() : "";
  const deviceName = typeof settings.deviceName === "string" && settings.deviceName.trim()
    ? settings.deviceName.trim()
    : defaultDeviceName();
  return {
    serverUrl,
    deviceName: deviceName.slice(0, 255),
    launchAtStartup: settings.launchAtStartup !== false
  };
}
