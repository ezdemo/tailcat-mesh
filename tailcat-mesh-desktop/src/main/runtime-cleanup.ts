import { execFile } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const CLEANUP_TIMEOUT_MS = 20_000;
const CLEANUP_OUTPUT_LIMIT = 128 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface RuntimeCleanupTarget {
  meshHome: string;
  dataDirectory: string;
  agentJarPath: string;
  configPath: string;
  adapterGuid: string;
  tun2SocksPath: string;
}

export interface RuntimeCleanupResult {
  activeAgentPids: number[];
  stoppedProcessIds: number[];
  removedAdapter: boolean;
  warnings: string[];
}

/**
 * Removes only resources that can be proven to belong to this Desktop
 * installation after its Agent is no longer running. A live Agent always
 * wins: the cleanup is skipped so a UI restart cannot interrupt the mesh.
 */
export async function cleanupOrphanedRuntime(
  target: RuntimeCleanupTarget
): Promise<RuntimeCleanupResult> {
  if (process.platform !== "win32") {
    return emptyResult();
  }

  const normalized = normalizeTarget(target);
  if (!normalized) {
    return {
      ...emptyResult(),
      warnings: ["Automatic runtime cleanup skipped because its target was not safe to resolve."]
    };
  }

  try {
    const result = await execFileAsync("powershell.exe", [
      "-NoLogo",
      "-NoProfile",
      "-NonInteractive",
      "-ExecutionPolicy",
      "Bypass",
      "-Command",
      buildRuntimeCleanupScript()
    ], {
      env: {
        ...process.env,
        TAILCAT_MESH_CLEANUP_MESH_HOME: normalized.meshHome,
        TAILCAT_MESH_CLEANUP_DATA_DIR: normalized.dataDirectory,
        TAILCAT_MESH_CLEANUP_AGENT_JAR: normalized.agentJarPath,
        TAILCAT_MESH_CLEANUP_CONFIG: normalized.configPath,
        TAILCAT_MESH_CLEANUP_ADAPTER_GUID: normalized.adapterGuid,
        TAILCAT_MESH_CLEANUP_TUN2SOCKS: normalized.tun2SocksPath
      },
      windowsHide: true,
      timeout: CLEANUP_TIMEOUT_MS,
      maxBuffer: CLEANUP_OUTPUT_LIMIT
    });
    return parseCleanupResult(result.stdout);
  } catch (error) {
    return {
      ...emptyResult(),
      warnings: [`Automatic runtime cleanup could not complete: ${errorMessage(error)}`]
    };
  }
}

/** Kept as a separate pure builder so the process/device boundary is testable. */
export function buildRuntimeCleanupScript(): string {
  return String.raw`
$meshHome = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_MESH_HOME')
$dataDirectory = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_DATA_DIR')
$agentJarPath = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_AGENT_JAR')
$configPath = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_CONFIG')
$adapterGuid = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_ADAPTER_GUID')
$tun2SocksPath = [Environment]::GetEnvironmentVariable('TAILCAT_MESH_CLEANUP_TUN2SOCKS')

function Contains-IgnoreCase([string] $value, [string] $needle) {
  return -not [string]::IsNullOrWhiteSpace($value) -and
    -not [string]::IsNullOrWhiteSpace($needle) -and
    $value.IndexOf($needle, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

$allProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
$activeAgents = @($allProcesses | Where-Object {
  $_.Name -ieq 'java.exe' -and
    (Contains-IgnoreCase $_.CommandLine $agentJarPath) -and
    (Contains-IgnoreCase $_.CommandLine $configPath) -and
    (Contains-IgnoreCase $_.CommandLine $dataDirectory)
})

$stoppedProcessIds = @()
$warnings = @()
$removedAdapter = $false

# Never touch child processes or adapters while a matching Java Agent exists.
if ($activeAgents.Count -eq 0) {
  $tailcatRoot = (Join-Path $meshHome 'tailcat').TrimEnd('\') + '\'
  $staleProcesses = @($allProcesses | Where-Object {
    (
      $_.Name -ieq 'tailcat.exe' -and
      $_.ExecutablePath -and
      $_.ExecutablePath.StartsWith($tailcatRoot, [StringComparison]::OrdinalIgnoreCase) -and
      (Contains-IgnoreCase $_.CommandLine $dataDirectory)
    ) -or (
      $_.Name -ieq 'tun2socks.exe' -and
      $_.ExecutablePath -ieq $tun2SocksPath -and
      (Contains-IgnoreCase $_.CommandLine $adapterGuid)
    )
  })

  foreach ($staleProcess in $staleProcesses) {
    try {
      Stop-Process -Id ([int]$staleProcess.ProcessId) -Force -ErrorAction Stop
      $stoppedProcessIds += [int]$staleProcess.ProcessId
    } catch {
      $warnings += ('Could not stop stale process {0}: {1}' -f $staleProcess.ProcessId, $_.Exception.Message)
    }
  }

  $instanceId = 'SWD\Wintun\{' + $adapterGuid + '}'
  $device = @(Get-PnpDevice -Class Net -PresentOnly:$false -ErrorAction SilentlyContinue |
    Where-Object { $_.InstanceId -ieq $instanceId })
  if ($device.Count -gt 0) {
    & pnputil.exe /remove-device $instanceId *> $null
    if ($LASTEXITCODE -eq 0) {
      $deadline = [DateTime]::UtcNow.AddSeconds(5)
      do {
        Start-Sleep -Milliseconds 200
        $stillPresent = @(Get-PnpDevice -Class Net -PresentOnly:$false -ErrorAction SilentlyContinue |
          Where-Object { $_.InstanceId -ieq $instanceId })
      } while ($stillPresent.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline)
      if ($stillPresent.Count -eq 0) {
        $removedAdapter = $true
      } else {
        $warnings += ('Tailcat Wintun device still exists after removal: {0}' -f $instanceId)
      }
    } else {
      $warnings += ('pnputil could not remove the Tailcat Wintun device: exit code {0}' -f $LASTEXITCODE)
    }
  }
}

[ordered]@{
  activeAgentPids = @($activeAgents | ForEach-Object { [int]$_.ProcessId })
  stoppedProcessIds = @($stoppedProcessIds)
  removedAdapter = [bool]$removedAdapter
  warnings = @($warnings)
} | ConvertTo-Json -Compress
`;
}

function normalizeTarget(target: RuntimeCleanupTarget): RuntimeCleanupTarget | null {
  const adapterGuid = target.adapterGuid.trim();
  if (!UUID_PATTERN.test(adapterGuid)) {
    return null;
  }
  try {
    const meshHome = path.resolve(target.meshHome);
    const dataDirectory = path.resolve(target.dataDirectory);
    const tun2SocksPath = path.resolve(target.tun2SocksPath);
    const dataRelative = path.relative(meshHome, dataDirectory);
    const tunRelative = path.relative(meshHome, tun2SocksPath);
    if (!isDescendant(dataRelative) || !isDescendant(tunRelative)) {
      return null;
    }
    return {
      meshHome,
      dataDirectory,
      agentJarPath: path.resolve(target.agentJarPath),
      configPath: path.resolve(target.configPath),
      adapterGuid,
      tun2SocksPath
    };
  } catch {
    return null;
  }
}

function isDescendant(relativePath: string): boolean {
  return relativePath.length > 0 && relativePath !== ".." && !relativePath.startsWith(`..${path.sep}`)
    && !path.isAbsolute(relativePath);
}

function parseCleanupResult(output: string): RuntimeCleanupResult {
  try {
    const value: unknown = JSON.parse(output.trim());
    if (typeof value !== "object" || value === null) {
      throw new Error("cleanup response was not an object");
    }
    const record = value as Record<string, unknown>;
    return {
      activeAgentPids: positiveIntegers(record.activeAgentPids),
      stoppedProcessIds: positiveIntegers(record.stoppedProcessIds),
      removedAdapter: record.removedAdapter === true,
      warnings: strings(record.warnings)
    };
  } catch (error) {
    return {
      ...emptyResult(),
      warnings: [`Automatic runtime cleanup returned an invalid response: ${errorMessage(error)}`]
    };
  }
}

function positiveIntegers(value: unknown): number[] {
  return Array.isArray(value)
    ? value.filter((item): item is number => typeof item === "number"
      && Number.isInteger(item) && item > 0)
    : [];
}

function strings(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string" && item.length > 0)
    : [];
}

function emptyResult(): RuntimeCleanupResult {
  return { activeAgentPids: [], stoppedProcessIds: [], removedAdapter: false, warnings: [] };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
