import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { buildAgentArguments } from "../dist/main/agent-process.js";
import { readAdapterGuid, renderGeneratedAgentConfig } from "../dist/main/agent-config.js";
import { createDesktopPaths, normalizedSettings } from "../dist/main/paths.js";
import { createUiModel } from "../dist/renderer/model.js";
import { buildRuntimeCleanupScript } from "../dist/main/runtime-cleanup.js";
import { createTranslator } from "../dist/shared/i18n.js";

test("generated Agent configuration keeps runtime ownership in Java", () => {
  const settings = normalizedSettings({
    serverUrl: "https://mesh.example.test/",
    deviceName: "DESKTOP-A",
    launchAtStartup: true
  });
  const token = "tm_enroll_should-never-be-written";
  const config = renderGeneratedAgentConfig("C:\\Users\\demo\\.tailcat-mesh\\data\\agent", settings,
    "11111111-2222-3333-4444-555555555555");

  assert.match(config, /server:\n  url: "https:\/\/mesh\.example\.test\/"/);
  assert.match(config, /device:\n  name: "DESKTOP-A"/);
  assert.match(config, /virtualLan:\n  enabled: true/);
  assert.match(config, /tun:\/\/\$\{tun\}/);
  assert.doesNotMatch(config, new RegExp(token));
  assert.equal(readAdapterGuid(config), "11111111-2222-3333-4444-555555555555");
});

test("generated Agent configuration includes an optional local proxy", () => {
  const settings = normalizedSettings({
    serverUrl: "http://mesh.example.test",
    deviceName: "DESKTOP-A",
    proxy: { type: "socks5", host: "127.0.0.1", port: 1080 }
  });
  const config = renderGeneratedAgentConfig("C:\\Users\\demo\\.tailcat-mesh\\data\\agent", settings,
    "11111111-2222-3333-4444-555555555555");

  assert.match(config, /proxy:\n  type: "socks5"\n  host: "127\.0\.0\.1"\n  port: 1080/);
});

test("Electron arguments launch the Java Agent directly", () => {
  const paths = createDesktopPaths(path.join("C:", "Program Files", "Tailcat Mesh", "resources"));
  const dataDirectory = path.join("C:", "Users", "demo", ".tailcat-mesh", "data", "agent");
  const args = buildAgentArguments(paths, dataDirectory, "connect", "tm_enroll_test");

  assert.deepEqual(args, [
    "-jar",
    paths.agentJarPath,
    "connect",
    "--config",
    paths.configPath,
    "--data-dir",
    dataDirectory,
    "--token",
    "tm_enroll_test"
  ]);
  assert.equal(args.some((value) => value.toLowerCase().includes("powershell")), false);
  assert.equal(args.some((value) => value.toLowerCase().endsWith(".ps1")), false);
  assert.equal(args.includes("tailcat.exe"), false);
  assert.equal(args.includes("tun2socks.exe"), false);
});

test("settings normalize to the small convention-over-configuration surface", () => {
  const settings = normalizedSettings({ serverUrl: "  https://mesh.example.test  ", launchAtStartup: false });
  assert.equal(settings.serverUrl, "https://mesh.example.test");
  assert.equal(settings.launchAtStartup, false);
  assert.equal(settings.startMinimized, true);
  assert.equal(settings.theme, "system");
  assert.equal(settings.language, "zh-CN");
  assert.ok(settings.deviceName.length > 0);
});

test("desktop language preference defaults to Chinese and supports English fallback", () => {
  const chinese = createTranslator("zh-CN");
  const english = createTranslator("en-US");
  assert.equal(chinese("Overview"), "概览");
  assert.equal(english("Overview"), "Overview");
  assert.equal(chinese("Direct"), "直连");
});

test("structured UI model keeps control-plane offline separate from mesh runtime", () => {
  const model = createUiModel({
    lifecycle: "running",
    mode: "existing",
    enrolled: true,
    pid: 42,
    exitCode: null,
    lastError: null,
    logTail: [{
      id: "test-log-1",
      timestamp: "2026-09-01T02:42:01.000Z",
      level: "INFO",
      component: "Agent",
      source: "stdout",
      message: "stdout: A log line must never determine UI state."
    }],
    status: {
      status: "ONLINE",
      controlPlaneStatus: "OFFLINE",
      deviceId: "device-a",
      deviceName: "DESKTOP-A",
      serverUrl: "https://mesh.example.test",
      agentState: "RUNNING",
      pid: 42,
      tailcatVersion: "0.3.0",
      tailcatState: "RUNNING",
      networks: [{
        networkId: "home",
        name: "home",
        cidr: "10.77.0.0/24",
        virtualIpv4: "10.77.0.2",
        status: "ACTIVE",
        path: "DERP",
        lastError: null
      }],
      lastError: null,
      updatedAt: new Date().toISOString()
    }
  }, normalizedSettings({ serverUrl: "https://mesh.example.test", deviceName: "DESKTOP-A" }));

  assert.equal(model.connection, "OFFLINE");
  assert.equal(model.controlServer, "OFFLINE");
  assert.equal(model.meshRuntime, "RUNNING");
  assert.equal(model.networks[0].status, "ACTIVE");
  assert.equal(model.networks[0].path, "DERP");
  assert.notEqual(model.logs[0].time, "—");
  assert.equal(model.logs[0].message, "stdout: A log line must never determine UI state.");
});

test("a stopped Agent is rendered as an Agent error, not control-plane reconnecting", () => {
  const model = createUiModel({
    lifecycle: "running",
    mode: "existing",
    enrolled: true,
    pid: 42,
    exitCode: null,
    lastError: "TUN adapter is unavailable",
    logTail: [],
    status: {
      status: "ONLINE",
      controlPlaneStatus: "UNKNOWN",
      deviceId: "device-a",
      deviceName: "DESKTOP-A",
      serverUrl: "https://mesh.example.test",
      agentState: "STOPPED",
      pid: 42,
      tailcatVersion: "0.3.0",
      tailcatState: "STOPPED",
      networks: [],
      lastError: "TUN adapter is unavailable",
      updatedAt: new Date().toISOString()
    }
  }, normalizedSettings({ serverUrl: "https://mesh.example.test", deviceName: "DESKTOP-A" }));

  assert.equal(model.connection, "ERROR");
  assert.equal(model.agent, "STOPPED");
  assert.equal(model.meshRuntime, "STOPPED");
});

test("automatic runtime cleanup is scoped to Tailcat-owned processes and one configured adapter", () => {
  const script = buildRuntimeCleanupScript();

  assert.match(script, /Name -ieq 'java\.exe'/);
  assert.match(script, /Name -ieq 'tailcat\.exe'/);
  assert.match(script, /Name -ieq 'tun2socks\.exe'/);
  assert.match(script, /pnputil\.exe \/remove-device/);
  assert.match(script, /activeAgents\.Count -eq 0/);
  assert.doesNotMatch(script, /taskkill\.exe.*\/IM/);
  assert.doesNotMatch(script, /Tailscale|WireGuard|OpenVPN/i);
});

test("the desktop renderer keeps enrollment and security boundaries explicit", async () => {
  const html = await readFile(new URL("../src/renderer/index.html", import.meta.url), "utf8");
  const renderer = await readFile(new URL("../src/renderer/renderer.ts", import.meta.url), "utf8");
  const enrollment = await readFile(new URL("../src/renderer/pages/enrollment.ts", import.meta.url), "utf8");
  const styles = await readFile(new URL("../src/renderer/styles.css", import.meta.url), "utf8");
  const main = await readFile(new URL("../src/main/main.ts", import.meta.url), "utf8");
  const shared = await readFile(new URL("../src/shared/types.ts", import.meta.url), "utf8");

  assert.match(html, /id="app-root"/);
  assert.match(enrollment, /id="server-url"/);
  assert.match(enrollment, /id="enrollment-token"/);
  assert.match(enrollment, /id="device-name"/);
  assert.match(enrollment, /id="connect-button"/);
  assert.doesNotMatch(enrollment, /Java path|Tailcat path|tun2socks|Wintun|SOCKS|TUN MTU|Route metric|ConnBlob|WireGuard Key|DERP region/i);
  assert.doesNotMatch(renderer, /connection-proxy|dashboard-proxy|proxy-nav/);
  assert.match(renderer, /mockUiModel/);
  assert.match(renderer, /createUiModel/);
  assert.match(main, /width: 1120/);
  assert.match(main, /minWidth: 920/);
  assert.match(main, /minHeight: 620/);
  assert.match(main, /nodeIntegration: false/);
  assert.match(main, /contextIsolation: true/);
  assert.match(shared, /ThemePreference/);
  assert.match(shared, /LanguagePreference/);
  assert.match(shared, /setLanguage/);
  assert.match(renderer, /language-choice/);
  assert.match(shared, /resetDevice/);
  assert.match(styles, /\.log-message[^}]*white-space: pre-wrap/);
  assert.match(styles, /\.log-message[^}]*overflow-wrap: anywhere/);
});
