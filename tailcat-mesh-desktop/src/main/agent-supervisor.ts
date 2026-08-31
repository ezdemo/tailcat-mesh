import { appendFile, access, mkdir, readFile } from "node:fs/promises";
import { execFile } from "node:child_process";
import http from "node:http";
import path from "node:path";
import type { ChildProcess } from "node:child_process";
import type {
  DesktopSettings,
  LocalAgentStatus,
  LocalStatusDescriptor,
  SupervisorState
} from "../shared/types.js";
import { resolveJava21, spawnAgent, type AgentCommand } from "./agent-process.js";
import { ensureAgentConfig } from "./agent-config.js";
import type { DesktopPaths } from "./paths.js";
import { defaultDeviceName, resolveConfiguredDataDirectory } from "./paths.js";

const MAX_AUTO_RESTARTS = 3;
const MAX_LOG_LINES = 200;
const STATUS_POLL_INTERVAL_MS = 1_000;
const GRACEFUL_STOP_TIMEOUT_MS = 10_000;

export class AgentSupervisor {
  private readonly paths: DesktopPaths;
  private readonly listeners = new Set<(state: SupervisorState) => void>();
  private child: ChildProcess | null = null;
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
  private logTail: string[] = [];
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
      pid: this.status?.pid ?? this.child?.pid ?? null,
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
    deviceName?: string
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
      launchAtStartup: true
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
    await this.launch("run");
    return this.getRuntimeState();
  }

  public async stop(): Promise<SupervisorState> {
    this.launchGeneration += 1;
    this.stopRequested = true;
    this.clearRestartTimer();
    this.clearPollTimer();
    const currentChild = this.child;
    if (currentChild) {
      const descriptor = await this.readDescriptor();
      if (descriptor) {
        await this.postLocal(descriptor, "/local/shutdown").catch(() => undefined);
      }
      await this.waitForExit(currentChild, GRACEFUL_STOP_TIMEOUT_MS);
      if (currentChild.exitCode === null && currentChild.pid) {
        await terminateProcessTree(descriptor?.pid ?? currentChild.pid);
        await terminateProcessTree(currentChild.pid);
        await this.waitForExit(currentChild, 2_000);
      }
    }
    this.child = null;
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

  public async reconnect(): Promise<SupervisorState> {
    const descriptor = await this.readDescriptor();
    if (!descriptor) {
      throw new Error("Agent local status channel is not ready");
    }
    await this.postLocal(descriptor, "/local/reconnect");
    return this.getRuntimeState();
  }

  public isRunning(): boolean {
    return this.child !== null && this.lifecycle !== "stopped" && this.lifecycle !== "error";
  }

  public getPid(): number | null {
    return this.status?.pid ?? this.child?.pid ?? null;
  }

  public getLastExitCode(): number | null {
    return this.lastExitCode;
  }

  public logPath(): string {
    return this.paths.logPath;
  }

  private async launch(command: AgentCommand, token?: string): Promise<void> {
    if (this.child) {
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
      await assertLaunchFiles(this.paths);
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
    if (this.mode === "existing" && this.restartAttempts < MAX_AUTO_RESTARTS) {
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
      return;
    }
    this.lastError = detail;
    this.lifecycle = "error";
    this.emit();
  }

  private startPolling(): void {
    this.clearPollTimer();
    const poll = async () => {
      if (!this.child || this.stopRequested) {
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
          await this.hasEnrollment();
        } finally {
          this.polling = false;
        }
      }
      if (this.child && !this.stopRequested) {
        this.pollTimer = setTimeout(() => void poll(), STATUS_POLL_INTERVAL_MS);
      }
    };
    void poll();
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

  private async recordOutput(kind: "stdout" | "stderr", text: string): Promise<void> {
    const redacted = this.activeToken ? text.split(this.activeToken).join("[redacted]") : text;
    const lines = redacted.split(/\r?\n/).filter((line) => line.length > 0);
    if (lines.length === 0) {
      return;
    }
    for (const line of lines) {
      this.logTail.push(`${kind}: ${line}`);
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
