import { execFileSync, spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const electronExecutable = path.resolve(
  projectRoot,
  "node_modules",
  "electron",
  "dist",
  process.platform === "win32" ? "electron.exe" : "electron"
);

if (process.platform !== "win32") {
  process.stdout.write("[desktop] stop-existing-electron: skipped (Windows-only cleanup)\n");
  process.exit(0);
}

const escapedExecutable = electronExecutable.replaceAll("'", "''");
const query = [
  `$electronExecutable = '${escapedExecutable}'`,
  "$processes = Get-CimInstance Win32_Process -Filter \"Name='electron.exe'\" |",
  "  Where-Object { $_.ExecutablePath -eq $electronExecutable -and $_.CommandLine -notmatch '--type=' } |",
  "  Select-Object -ExpandProperty ProcessId",
  "$processes"
].join("\n");

let output = "";
try {
  output = execFileSync("powershell.exe", [
    "-NoLogo",
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-Command",
    query
  ], { encoding: "utf8", windowsHide: true });
} catch (error) {
  process.stderr.write(`[desktop] unable to inspect old Electron processes: ${errorMessage(error)}\n`);
  process.exit(1);
}

const processIds = [...new Set(
  output
    .split(/\r?\n/)
    .map((value) => Number.parseInt(value.trim(), 10))
    .filter((value) => Number.isInteger(value) && value > 0 && value !== process.pid)
)];

for (const processId of processIds) {
  // Do not use /T here. The Java Agent is intentionally a child of Electron,
  // but it is the long-lived mesh runtime and must survive a UI restart.
  const result = spawnSync("taskkill.exe", ["/PID", String(processId), "/F"], {
    stdio: "ignore",
    windowsHide: true
  });
  if (result.status === 0) {
    process.stdout.write(`[desktop] stopped previous Electron instance (PID ${processId})\n`);
  } else if (result.error) {
    process.stderr.write(`[desktop] failed to stop Electron PID ${processId}: ${result.error.message}\n`);
    process.exitCode = 1;
  }
}

if (processIds.length === 0) {
  process.stdout.write("[desktop] no previous Electron instance found\n");
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}
