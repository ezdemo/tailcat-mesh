import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { buildAgentArguments } from "../dist/main/agent-process.js";
import { readAdapterGuid, renderGeneratedAgentConfig } from "../dist/main/agent-config.js";
import { createDesktopPaths, normalizedSettings } from "../dist/main/paths.js";

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
  assert.ok(settings.deviceName.length > 0);
});

test("the first-run connection form is present and visible by default", async () => {
  const html = await readFile(new URL("../src/renderer/index.html", import.meta.url), "utf8");

  assert.match(html, /id="connection"/);
  assert.match(html, /id="server-url"/);
  assert.match(html, /id="enrollment-token"/);
  assert.match(html, /id="connect-button"/);
  assert.doesNotMatch(html, /id="onboarding"[^>]*hidden/);
});
