import { access, copyFile, mkdir, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const desktopRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(desktopRoot, "..");
const target = path.join(desktopRoot, "resources", "agent", "tailcat-mesh-agent.jar");
const explicit = process.env.TAILCAT_MESH_AGENT_JAR;

const candidates = [];
if (explicit) {
  candidates.push(path.resolve(explicit));
}
candidates.push(
  path.join(repositoryRoot, "tailcat-mesh-agent", "target", "tailcat-mesh-agent-0.1.0-SNAPSHOT.jar"),
  path.join(desktopRoot, "resources", "agent", "tailcat-mesh-agent.jar")
);

try {
  const targetDirectory = path.join(repositoryRoot, "tailcat-mesh-agent", "target");
  const targetEntries = await readdir(targetDirectory, { withFileTypes: true });
  const discovered = targetEntries
    .filter((entry) => entry.isFile()
      && entry.name.startsWith("tailcat-mesh-agent-")
      && entry.name.endsWith(".jar")
      && !entry.name.startsWith("original-"))
    .map((entry) => path.join(targetDirectory, entry.name));
  candidates.push(...discovered);
} catch {
  // The explicit and conventional paths below produce the useful error.
}

let source;
for (const candidate of [...new Set(candidates)]) {
  try {
    await access(candidate);
    source = candidate;
    break;
  } catch {
    // Try the next build output.
  }
}

if (!source) {
  throw new Error(
    "Tailcat Mesh Agent JAR not found. Run Maven package first or set TAILCAT_MESH_AGENT_JAR."
  );
}

await mkdir(path.dirname(target), { recursive: true });
if (path.resolve(source) !== path.resolve(target)) {
  await copyFile(source, target);
}
console.log(`Staged Agent JAR from ${source}`);
