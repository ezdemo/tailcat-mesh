import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const desktopRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(desktopRoot, "..");
const mavenExecutable = process.platform === "win32" ? "mvn.cmd" : "mvn";
const argumentsList = [
  "--batch-mode",
  "--no-transfer-progress",
  "-f",
  path.join(repositoryRoot, "pom.xml"),
  "-pl",
  "tailcat-mesh-agent",
  "-am",
  "package",
  "-DskipTests"
];

try {
  const exitCode = await runMaven();
  if (exitCode !== 0) {
    process.exitCode = exitCode;
  }
} catch (error) {
  console.error(`Failed to build the Java Agent: ${errorMessage(error)}`);
  process.exitCode = 1;
}

function runMaven() {
  return new Promise((resolve, reject) => {
    const windows = process.platform === "win32";
    const command = windows
      ? [mavenExecutable, ...argumentsList].map(quoteCommandArgument).join(" ")
      : undefined;
    const child = spawn(
      windows ? (process.env.ComSpec || "cmd.exe") : mavenExecutable,
      windows ? ["/d", "/s", "/c", command] : argumentsList,
      {
        cwd: repositoryRoot,
        windowsHide: true,
        stdio: "inherit"
      }
    );
    child.once("error", reject);
    child.once("exit", (code) => resolve(code ?? 1));
  });
}

function quoteCommandArgument(value) {
  return /[\s"&|<>^]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}
