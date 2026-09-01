import { access, readdir } from "node:fs/promises";
import { execFile, spawn, type ChildProcess } from "node:child_process";
import { homedir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import type { DesktopPaths } from "./paths.js";

const execFileAsync = promisify(execFile);
const JAVA_VERSION_TIMEOUT_MS = 5_000;
const JAVA_VERSION_BUFFER_BYTES = 128 * 1024;

export type AgentCommand = "connect" | "run";

export interface AgentLaunchOptions {
  javaPath: string;
  paths: DesktopPaths;
  dataDirectory: string;
  command: AgentCommand;
  token?: string;
  onStdout: (text: string) => void;
  onStderr: (text: string) => void;
}

/**
 * Builds the complete Java Agent command line. Electron owns this boundary;
 * no shell, PowerShell, or intermediate launcher is involved.
 */
export function buildAgentArguments(
  paths: DesktopPaths,
  dataDirectory: string,
  command: AgentCommand,
  token?: string
): string[] {
  const argumentsList = [
    "-jar",
    paths.agentJarPath,
    command,
    "--config",
    paths.configPath,
    "--data-dir",
    dataDirectory
  ];
  if (token?.trim()) {
    argumentsList.push("--token", token.trim());
  }
  return argumentsList;
}

/** Starts the Java Agent directly as a child of Electron's main process. */
export function spawnAgent(options: AgentLaunchOptions): ChildProcess {
  if (process.platform !== "win32") {
    throw new Error("Tailcat Mesh Desktop currently supports Windows only");
  }
  const child = spawn(
    options.javaPath,
    buildAgentArguments(options.paths, options.dataDirectory, options.command, options.token),
    {
      cwd: options.paths.resourceRoot,
      windowsHide: true,
      detached: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"]
    }
  );
  child.stdout?.setEncoding("utf8");
  child.stderr?.setEncoding("utf8");
  child.stdout?.on("data", options.onStdout);
  child.stderr?.on("data", options.onStderr);
  return child;
}

/**
 * Resolves an installed Java 21+ runtime without relying on shell lookup or a
 * PowerShell script. The order keeps the per-user runtime contract first,
 * then honors JAVA_HOME and finally falls back to PATH.
 */
export async function resolveJava21(): Promise<string> {
  const userProfile = process.env.USERPROFILE?.trim() || homedir();
  const runtimeRoot = path.join(userProfile, ".tailcat-mesh", "runtime", "java");
  const candidates: string[] = [];

  addCandidate(candidates, path.join(runtimeRoot, "current", "bin", javaExecutableName()));
  for (const runtimeDirectory of await runtimeDirectories(runtimeRoot)) {
    addCandidate(candidates, path.join(runtimeDirectory, "bin", javaExecutableName()));
  }

  const javaHome = process.env.JAVA_HOME?.trim();
  if (javaHome) {
    addCandidate(candidates, path.join(javaHome, "bin", javaExecutableName()));
  }
  addCandidate(
    candidates,
    path.join(userProfile, ".sdkman", "candidates", "java", "current", "bin", javaExecutableName())
  );

  for (const candidate of candidates) {
    if (await isJava21OrNewer(candidate)) {
      return candidate;
    }
  }

  const pathCommand = process.platform === "win32" ? "java.exe" : "java";
  if (await isJava21OrNewer(pathCommand)) {
    return pathCommand;
  }

  throw new Error(
    "Java 21 or newer is required. Install Java 21 or provision it under %USERPROFILE%\\.tailcat-mesh\\runtime\\java."
  );
}

function javaExecutableName(): string {
  return process.platform === "win32" ? "java.exe" : "java";
}

async function runtimeDirectories(runtimeRoot: string): Promise<string[]> {
  try {
    const entries = await readdir(runtimeRoot, { withFileTypes: true });
    return entries
      .filter((entry) => entry.isDirectory() && entry.name.toLowerCase() !== "current")
      .sort((left, right) => right.name.localeCompare(left.name, undefined, { numeric: true }))
      .map((entry) => path.join(runtimeRoot, entry.name));
  } catch {
    return [];
  }
}

function addCandidate(candidates: string[], candidate: string): void {
  const normalized = path.resolve(candidate);
  const duplicate = candidates.some((existing) =>
    path.resolve(existing).toLowerCase() === normalized.toLowerCase());
  if (!duplicate) {
    candidates.push(normalized);
  }
}

async function isJava21OrNewer(candidate: string): Promise<boolean> {
  if (path.isAbsolute(candidate)) {
    try {
      await access(candidate);
    } catch {
      return false;
    }
  }
  const majorVersion = await javaMajorVersion(candidate);
  return majorVersion !== null && majorVersion >= 21;
}

async function javaMajorVersion(candidate: string): Promise<number | null> {
  try {
    const result = await execFileAsync(candidate, ["-version"], {
      windowsHide: true,
      timeout: JAVA_VERSION_TIMEOUT_MS,
      maxBuffer: JAVA_VERSION_BUFFER_BYTES
    });
    return parseJavaMajorVersion(`${result.stdout ?? ""}\n${result.stderr ?? ""}`);
  } catch (error) {
    const output = isRecord(error)
      ? `${typeof error.stdout === "string" ? error.stdout : ""}\n${typeof error.stderr === "string" ? error.stderr : ""}`
      : "";
    return parseJavaMajorVersion(output);
  }
}

function parseJavaMajorVersion(output: string): number | null {
  const match = output.match(/(?:version|openjdk)\s+["']?([0-9]+(?:\.[0-9]+)?)/i);
  if (!match?.[1]) {
    return null;
  }
  const parts = match[1].split(".");
  const first = Number(parts[0]);
  if (!Number.isInteger(first)) {
    return null;
  }
  if (first === 1 && parts[1]) {
    const legacyMajor = Number(parts[1]);
    return Number.isInteger(legacyMajor) ? legacyMajor : null;
  }
  return first;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
