import { appendFile, access, mkdir, readFile, rm } from "node:fs/promises";
import { execFile } from "node:child_process";
import http from "node:http";
import path from "node:path";
import type { ChildProcess } from "node:child_process";
import type {
  DesktopSettings,
  LocalProxySettings,
  LocalAgentStatus,
  LocalStatusDescriptor,
  RuntimeLogEntry,
  SupervisorState
} from "../shared/types.js";
import { resolveJava21, spawnAgent, type AgentCommand } from "./agent-process.js";
import { ensureAgentConfig, readAdapterGuid } from "./agent-config.js";
import type { DesktopPaths } from "./paths.js";
import { defaultDeviceName, resolveConfiguredDataDirectory } from "./paths.js";
import { cleanupOrphanedRuntime, type RuntimeCleanupResult } from "./runtime-cleanup.js";

const MAX_AUTO_RESTARTS = 3;
const MAX_LOG_LINES = 200;
const MAX_PERSISTED_LOG_CHARS = 512 * 1024;
const STATUS_POLL_INTERVAL_MS = 1_000;
const GRACEFUL_STOP_TIMEOUT_MS = 10_000;

export class AgentSupervisor {
  private readonly paths: DesktopPaths;
  private readonly listeners = new Set<(state: SupervisorState) => void>();
  private child: ChildProcess | null = null;
  private adoptedPid: number | null = null;
  private launchGeneration = 0;
  private activeDataDirectory: string;
  private mode: "first-enrollment" | "existing" | null = null;
  private stopRequested = false;
  private restartAttempts = 0;
  private restartTimer: NodeJS.Timeout | null = null;
  private pollTimer: NodeJS.Timeout | null = null;
  private polling = false;
  private activeToken: string | null = null;
  private enrolled = false;
  private lastExitCode: number | null = null;
  private lastError: string | null = null;
  private status: LocalAgentStatus | null = null;
  private logTail: RuntimeLogEntry[] = [];
  private logSequence = 0;
  private lifecycle: SupervisorState["lifecycle"] = "stopped";

  public constructor(paths: DesktopPaths) {
    this.paths = paths;
    this.activeDataDirectory = paths.dataDirectory;
  }

  public onStateChange(listener: (state: SupervisorState) => void): () => void {
    this.listeners.add(listener);
    listener(this.getRuntimeState());
    return () => this.listeners.delete(listener);
  }

  public getRuntimeState(): SupervisorState {
    return {
      lifecycle: this.lifecycle,
      mode: this.mode,
      enrolled: this.enrolled,
      pid: this.status?.pid ?? this.child?.pid ?? this.adoptedPid ?? null,
      exitCode: this.lastExitCode,
      status: this.status,
      lastError: this.lastError,
      logTail: [...this.logTail]
    };
  }

  public async hasEnrollment(): Promise<boolean> {
    this.activeDataDirectory = resolveConfiguredDataDirectory(
      this.paths.configPath,
      this.paths.dataDirectory
    );
    const statePath = path.join(this.activeDataDirectory, "identity", "agent-state.json");
    try {
      await access(statePath);
      this.enrolled = true;
      return true;
    } catch {
      this.enrolled = false;
      return false;
    }
  }

  public async startFirstEnrollment(
    serverUrl: string,
    token: string,
    deviceName?: string,
    proxy?: LocalProxySettings
  ): Promise<SupervisorState> {
    const normalizedUrl = validateServerUrl(serverUrl);
    const normalizedToken = token.trim();
    if (!normalizedToken) {
      throw new Error("Enrollment Token is required");
    }
    await this.stop();
    const settings: DesktopSettings = {
      serverUrl: normalizedUrl,
      deviceName: deviceName?.trim() || defaultDeviceName(),
      launchAtStartup: true,
      startMinimized: true,
      theme: "system",
      language: "zh-CN",
      proxy: proxy ?? noProxy()
    };
    const config = await ensureAgentConfig(this.paths, settings);
    this.activeDataDirectory = config.dataDirectory;
    this.enrolled = false;
    this.mode = "first-enrollment";
    this.restartAttempts = 0;
    this.activeToken = normalizedToken;
    await this.launch("connect", normalizedToken);
    return this.getRuntimeState();
  }

  public async startExisting(): Promise<SupervisorState> {
    await this.stop();
    const enrolled = await this.hasEnrollment();
    if (!enrolled) {
      throw new Error("This device is not enrolled yet");
    }
    this.mode = "existing";
    this.restartAttempts = 0;
    this.activeToken = null;
    await this.loadPersistedLogTail();
    const descriptor = await this.readDescriptor();
    if (descriptor) {
      let existingStatus: LocalAgentStatus | null = null;
      try {
        existingStatus = await this.getLocalStatus(descriptor);
      } catch {
        // A descriptor can outlive a crashed Agent. Treat a refused or
        // unavailable local endpoint as stale metadata and continue with
        // the PID/launch decision below.
      }
      if (existingStatus) {
        this.adoptedPid = descriptor.pid;
        this.status = existingStatus;
        this.enrolled = Boolean(existingStatus.deviceId);
        this.lifecycle = "running";
        this.emit();
        this.startPolling();
        return this.getRuntimeState();
      }
      // A restarted Desktop can observe the descriptor before the Agent's
      // loopback HTTP server is ready. If the recorded process is still
      // alive, adopt it and keep polling instead of launching a duplicate
      // Agent that would race for the same Wintun/TUN resources.
      if (isProcessRunning(descriptor.pid)) {
        this.adoptedPid = descriptor.pid;
        this.lifecycle = "starting";
        this.emit();
        this.startPolling();
        return this.getRuntimeState();
      }
    }
    await this.launch("run");
    return this.getRuntimeState();
  }

  public async stop(): Promise<SupervisorState> {
    this.launchGeneration += 1;
    this.stopRequested = true;
    this.clearRestartTimer();
    this.clearPollTimer();
    const currentChild = this.child;
    const adoptedPid = this.adoptedPid;
    const descriptor = await this.readDescriptor();
    if (currentChild || adoptedPid) {
      if (descriptor) {
        await this.postLocal(descriptor, "/local/shutdown").catch(() => undefined);
      }
      if (currentChild) {
        await this.waitForExit(currentChild, GRACEFUL_STOP_TIMEOUT_MS);
        if (currentChild.exitCode === null && currentChild.pid) {
          await terminateProcessTree(descriptor?.pid ?? currentChild.pid);
          await terminateProcessTree(currentChild.pid);
          await this.waitForExit(currentChild, 2_000);
        }
      } else if (adoptedPid) {
        await waitForProcessExit(adoptedPid, GRACEFUL_STOP_TIMEOUT_MS);
        if (isProcessRunning(adoptedPid)) {
          await terminateProcessTree(adoptedPid);
        }
      }
    }
    this.child = null;
    this.adoptedPid = null;
    this.activeToken = null;
    this.mode = null;
    this.status = null;
    this.lastExitCode = null;
    this.lastError = null;
    this.lifecycle = "stopped";
    this.emit();
    return this.getRuntimeState();
  }

  public async restart(): Promise<SupervisorState> {
    const enrolled = await this.hasEnrollment();
    if (!enrolled) {
      throw new Error("This device is not enrolled yet");
    }
    await this.stop();
    this.mode = "existing";
    this.restartAttempts = 0;
    await this.launch("run");
    return this.getRuntimeState();
  }

  public async applySettings(settings: DesktopSettings): Promise<SupervisorState> {
    const enrolled = await this.hasEnrollment();
    if (!enrolled) {
      return this.getRuntimeState();
    }
    const config = await ensureAgentConfig(this.paths, settings);
    this.activeDataDirectory = config.dataDirectory;
    return this.restart();
  }

  public async reconnect(): Promise<SupervisorState> {
    const descriptor = await this.readDescriptor();
    if (!descriptor) {
      throw new Error("Agent local status channel is not ready");
    }
    await this.postLocal(descriptor, "/local/reconnect");
    return this.getRuntimeState();
  }

  public async resetDevice(): Promise<SupervisorState> {
    await this.stop();
    await rm(path.join(this.activeDataDirectory, "identity"), { recursive: true, force: true });
    this.enrolled = false;
    this.emit();
    return this.getRuntimeState();
  }

  public isRunning(): boolean {
    return (this.child !== null || this.adoptedPid !== null)
      && this.lifecycle !== "stopped" && this.lifecycle !== "error";
  }

  public getPid(): number | null {
    return this.status?.pid ?? this.child?.pid ?? this.adoptedPid ?? null;
  }

  public getLastExitCode(): number | null {
    return this.lastExitCode;
  }

  public logPath(): string {
    return this.paths.logPath;
  }

  public dataPath(): string {
    return this.activeDataDirectory;
  }

  private async launch(command: AgentCommand, token?: string): Promise<void> {
    if (this.child || this.adoptedPid) {
      return;
    }
    const generation = ++this.launchGeneration;
    this.stopRequested = false;
    this.lastExitCode = null;
    this.lastError = null;
    this.status = null;
    this.lifecycle = "starting";
    this.emit();
    try {
      await this.loadPersistedLogTail();
      await assertLaunchFiles(this.paths);
      const cleanup = await this.prepareRuntimeForLaunch();
      if (generation !== this.launchGeneration || this.stopRequested || this.child) {
        return;
      }
      const existingAgentPid = cleanup.activeAgentPids.at(0);
      if (existingAgentPid !== undefined) {
        // The process inspection found a matching Agent even though its local
        // status endpoint was not ready yet. Adopt it instead of duplicating
        // the runtime and competing for its network resources.
        this.activeToken = null;
        this.adoptedPid = existingAgentPid;
        this.lifecycle = "starting";
        this.emit();
        this.startPolling();
        return;
      }
      const javaPath = await resolveJava21();
      if (generation !== this.launchGeneration || this.stopRequested || this.child) {
        return;
      }
      const child = spawnAgent({
        javaPath,
        paths: this.paths,
        dataDirectory: this.activeDataDirectory,
        command,
        ...(token ? { token } : {}),
        onStdout: (text) => this.recordOutput("stdout", text),
        onStderr: (text) => this.recordOutput("stderr", text)
      });
      this.child = child;
      child.once("error", (error) => {
        this.lastError = error.message;
        this.lifecycle = "error";
        this.emit();
      });
      child.once("exit", (code) => {
        void this.handleExit(child, code);
      });
      this.startPolling();
    } catch (error) {
      if (generation !== this.launchGeneration || this.stopRequested) {
        return;
      }
      this.lastError = errorMessage(error);
      this.lifecycle = "error";
      this.emit();
      throw error;
    }
  }

  private async prepareRuntimeForLaunch(): Promise<RuntimeCleanupResult> {
    let configContent: string;
    try {
      configContent = await readFile(this.paths.configPath, "utf8");
    } catch {
      return emptyCleanupResult();
    }
    const adapterGuid = readAdapterGuid(configContent);
    if (!adapterGuid) {
      return emptyCleanupResult();
    }
    const result = await cleanupOrphanedRuntime({
      meshHome: this.paths.meshHome,
      dataDirectory: this.activeDataDirectory,
      agentJarPath: this.paths.agentJarPath,
      configPath: this.paths.configPath,
      adapterGuid,
      tun2SocksPath: path.join(this.paths.meshHome, "virtual-lan", "windows", "tun2socks.exe")
    });
    const details = [
      result.stoppedProcessIds.length > 0
        ? `stopped stale runtime processes: ${result.stoppedProcessIds.join(", ")}` : null,
      result.removedAdapter ? "removed stale Tailcat Wintun adapter" : null,
      ...result.warnings
    ].filter((detail): detail is string => detail !== null);
    if (details.length > 0) {
      await this.recordOutput("system", `[runtime-cleanup] ${details.join("; ")}`);
    }
    return result;
  }

  private async loadPersistedLogTail(): Promise<void> {
    if (this.logTail.length > 0) {
      return;
    }
    try {
      const raw = await readFile(this.paths.logPath, "utf8");
      const lines = raw
        .slice(-MAX_PERSISTED_LOG_CHARS)
        .split(/\r?\n/)
        .filter((line) => line.length > 0)
        .slice(-MAX_LOG_LINES);
      const entries: RuntimeLogEntry[] = [];
      for (const line of lines) {
        const parsed = parsePersistedLogLine(line);
        if (!parsed) {
          continue;
        }
        entries.push({
          id: `runtime-${++this.logSequence}`,
          timestamp: parsed.timestamp,
          level: parsed.source === "stderr" ? "ERROR" : "INFO",
          component: parsed.source === "system" ? "Desktop" : "Agent",
          source: parsed.source,
          message: `${parsed.source}: ${parsed.message}`
        });
      }
      if (entries.length > 0) {
        this.logTail = entries;
        this.emit();
      }
    } catch {
      // A missing or unreadable persisted log must not stop Agent startup.
    }
  }

  private async handleExit(child: ChildProcess, code: number | null): Promise<void> {
    if (this.child !== child) {
      return;
    }
    this.clearPollTimer();
    this.child = null;
    this.lastExitCode = code;
    this.activeToken = null;
    const expected = this.stopRequested;
    if (expected) {
      this.lifecycle = "stopped";
      this.emit();
      return;
    }
    const detail = `Java Agent exited${code === null ? "" : ` with code ${code}`}`;
    this.scheduleRestartOrError(detail);
  }

  private scheduleRestartOrError(detail: string): void {
    if (this.mode !== "existing" || this.restartAttempts >= MAX_AUTO_RESTARTS) {
      this.lastError = detail;
      this.lifecycle = "error";
      this.emit();
      return;
    }
    this.restartAttempts += 1;
    this.lastError = `${detail}; restarting (${this.restartAttempts}/${MAX_AUTO_RESTARTS})`;
    this.lifecycle = "starting";
    this.emit();
    const delay = Math.min(1_000 * (2 ** (this.restartAttempts - 1)), 8_000);
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null;
      if (!this.stopRequested && this.mode === "existing") {
        void this.launch("run").catch(() => undefined);
      }
    }, delay);
  }

  private startPolling(): void {
    this.clearPollTimer();
    const poll = async () => {
      if ((!this.child && !this.adoptedPid) || this.stopRequested) {
        return;
      }
      if (!this.polling) {
        this.polling = true;
        try {
          const descriptor = await this.readDescriptor();
          if (descriptor) {
            const status = await this.getLocalStatus(descriptor);
            if (status) {
              this.status = status;
              this.enrolled = Boolean(status.deviceId);
              this.lifecycle = "running";
              this.lastError = status.lastError;
              this.emit();
            }
          }
          if (this.adoptedPid && !isProcessRunning(this.adoptedPid)) {
            this.adoptedPid = null;
            this.status = null;
            this.scheduleRestartOrError("Java Agent exited unexpectedly");
          }
          await this.hasEnrollment();
        } catch {
          // The Agent can publish its descriptor before the local HTTP
          // endpoint is ready. Keep polling instead of creating an
          // unhandled rejection during normal startup/restart races.
        } finally {
          this.polling = false;
        }
      }
      if ((this.child || this.adoptedPid) && !this.stopRequested) {
        this.pollTimer = setTimeout(() => void poll().catch(() => undefined), STATUS_POLL_INTERVAL_MS);
      }
    };
    void poll().catch(() => undefined);
  }

  private async readDescriptor(): Promise<LocalStatusDescriptor | null> {
    try {
      const raw = await readFile(path.join(this.activeDataDirectory, "local-status.json"), "utf8");
      const value: unknown = JSON.parse(raw);
      if (!isRecord(value)
        || typeof value.port !== "number"
        || !Number.isInteger(value.port)
        || value.port < 1
        || value.port > 65_535
        || typeof value.token !== "string"
        || !value.token
        || typeof value.pid !== "number"
        || !Number.isInteger(value.pid)
        || value.pid < 1) {
        return null;
      }
      return { port: value.port, token: value.token, pid: value.pid };
    } catch {
      return null;
    }
  }

  private async getLocalStatus(descriptor: LocalStatusDescriptor): Promise<LocalAgentStatus | null> {
    return requestJson<LocalAgentStatus>(descriptor, "/local/status", "GET");
  }

  private async postLocal(descriptor: LocalStatusDescriptor, endpoint: string): Promise<void> {
    await requestJson(descriptor, endpoint, "POST");
  }

  private async waitForExit(child: ChildProcess, timeoutMs: number): Promise<void> {
    if (child.exitCode !== null || child.killed) {
      return;
    }
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, timeoutMs);
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }

  private async recordOutput(kind: "stdout" | "stderr" | "system", text: string): Promise<void> {
    const redacted = this.activeToken ? text.split(this.activeToken).join("[redacted]") : text;
    const lines = redacted.split(/\r?\n/).filter((line) => line.length > 0);
    if (lines.length === 0) {
      return;
    }
    const timestamp = new Date().toISOString();
    const level = kind === "stderr" ? "ERROR" : "INFO";
    const component = kind === "system" ? "Desktop" : "Agent";
    for (const line of lines) {
      this.logTail.push({
        id: `runtime-${++this.logSequence}`,
        timestamp,
        level,
        component,
        source: kind,
        message: `${kind}: ${line}`
      });
      if (this.logTail.length > MAX_LOG_LINES) {
        this.logTail.shift();
      }
    }
    this.emit();
    try {
      await mkdir(this.paths.logsDirectory, { recursive: true });
      const prefix = new Date().toISOString();
      await appendFile(
        this.paths.logPath,
        lines.map((line) => `${prefix} ${kind}: ${line}\n`).join(""),
        "utf8"
      );
    } catch {
      // A failed log file must not stop Agent supervision.
    }
  }

  private clearPollTimer(): void {
    if (this.pollTimer) {
      clearTimeout(this.pollTimer);
      this.pollTimer = null;
    }
  }

  private clearRestartTimer(): void {
    if (this.restartTimer) {
      clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
  }

  private emit(): void {
    const state = this.getRuntimeState();
    for (const listener of this.listeners) {
      listener(state);
    }
  }
}

async function assertLaunchFiles(paths: DesktopPaths): Promise<void> {
  try {
    await access(paths.agentJarPath);
  } catch {
    throw new Error(`Java Agent JAR was not found: ${paths.agentJarPath}`);
  }
  try {
    await access(paths.configPath);
  } catch {
    throw new Error(`Agent configuration was not found: ${paths.configPath}`);
  }
}

function validateServerUrl(value: string): string {
  try {
    const url = new URL(value.trim());
    if (url.protocol !== "http:" && url.protocol !== "https:") {
      throw new Error("Server URL must use HTTP or HTTPS");
    }
    if (!url.hostname) {
      throw new Error("Server URL must include a host");
    }
    return url.toString().replace(/\/$/, "");
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : "Server URL is invalid");
  }
}

function requestJson<T>(
  descriptor: LocalStatusDescriptor,
  endpoint: string,
  method: "GET" | "POST"
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const request = http.request({
      hostname: "127.0.0.1",
      port: descriptor.port,
      path: endpoint,
      method,
      timeout: 2_000,
      headers: {
        Authorization: `Bearer ${descriptor.token}`,
        Accept: "application/json"
      }
    }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk: string) => {
        body += chunk;
        if (body.length > 1_000_000) {
          request.destroy(new Error("local status response is too large"));
        }
      });
      response.once("end", () => {
        if ((response.statusCode ?? 500) < 200 || (response.statusCode ?? 500) >= 300) {
          reject(new Error(`Agent local API returned HTTP ${response.statusCode ?? 500}`));
          return;
        }
        if (!body) {
          resolve(undefined as T);
          return;
        }
        try {
          resolve(JSON.parse(body) as T);
        } catch (error) {
          reject(error);
        }
      });
    });
    request.once("error", reject);
    request.once("timeout", () => request.destroy(new Error("Agent local API timed out")));
    request.end();
  });
}

function terminateProcessTree(pid: number): Promise<void> {
  if (process.platform !== "win32") {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    execFile("taskkill.exe", ["/PID", String(pid), "/T", "/F"], () => resolve());
  });
}

function isProcessRunning(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function waitForProcessExit(pid: number, timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const check = () => {
      if (!isProcessRunning(pid) || Date.now() >= deadline) {
        resolve();
        return;
      }
      setTimeout(check, 100);
    };
    check();
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function parsePersistedLogLine(line: string): {
  timestamp: string;
  source: "stdout" | "stderr" | "system";
  message: string;
} | null {
  const match = line.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z) (stdout|stderr|system): (.*)$/);
  if (!match?.[1] || !match[2] || match[3] === undefined) {
    return null;
  }
  return {
    timestamp: match[1],
    source: match[2] as "stdout" | "stderr" | "system",
    message: match[3]
  };
}

function emptyCleanupResult(): RuntimeCleanupResult {
  return { activeAgentPids: [], stoppedProcessIds: [], removedAdapter: false, warnings: [] };
}

function noProxy(): LocalProxySettings {
  return { type: "none", host: "", port: null };
}
