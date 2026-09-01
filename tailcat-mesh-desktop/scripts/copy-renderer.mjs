import { copyFile, mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const desktopRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const rendererSource = path.join(desktopRoot, "src", "renderer");
const rendererTarget = path.join(desktopRoot, "dist", "renderer");
const rendererAssetsTarget = path.join(rendererTarget, "assets");

await mkdir(rendererTarget, { recursive: true });
await mkdir(rendererAssetsTarget, { recursive: true });
await Promise.all([
  copyFile(path.join(rendererSource, "index.html"), path.join(rendererTarget, "index.html")),
  copyFile(path.join(rendererSource, "styles.css"), path.join(rendererTarget, "styles.css")),
  copyFile(path.join(desktopRoot, "resources", "tailcat-mesh-logo.png"), path.join(rendererAssetsTarget, "tailcat-mesh-logo.png"))
]);
