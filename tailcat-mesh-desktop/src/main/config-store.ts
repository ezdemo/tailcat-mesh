import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import type { DesktopSettings } from "../shared/types.js";
import { defaultDeviceName, normalizedSettings } from "./paths.js";

export class ConfigStore {
  private readonly settingsPath: string;

  public constructor(userDataDirectory: string) {
    this.settingsPath = path.join(userDataDirectory, "settings.json");
  }

  public async load(): Promise<DesktopSettings> {
    try {
      const raw = await readFile(this.settingsPath, "utf8");
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== "object" || parsed === null) {
        return this.defaults();
      }
      return normalizedSettings(parsed as Partial<DesktopSettings>);
    } catch {
      return this.defaults();
    }
  }

  public async save(settings: Partial<DesktopSettings>): Promise<DesktopSettings> {
    const current = await this.load();
    const next = normalizedSettings({ ...current, ...settings });
    await mkdir(path.dirname(this.settingsPath), { recursive: true });
    const temporary = `${this.settingsPath}.${process.pid}.tmp`;
    await writeFile(temporary, `${JSON.stringify(next, null, 2)}\n`, "utf8");
    await rename(temporary, this.settingsPath);
    return next;
  }

  public path(): string {
    return this.settingsPath;
  }

  private defaults(): DesktopSettings {
    return normalizedSettings({
      serverUrl: "",
      deviceName: defaultDeviceName(),
      launchAtStartup: true,
      startMinimized: true,
      theme: "system",
      language: "zh-CN",
      proxy: { type: "none", host: "", port: null }
    });
  }
}
