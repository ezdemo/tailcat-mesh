function Resolve-Java21 {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += Join-Path $env:JAVA_HOME "bin\java.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $runtimeRoot = Join-Path $env:USERPROFILE ".tailcat-mesh\runtime\java"
        $candidates += Join-Path $runtimeRoot "current\bin\java.exe"
        if (Test-Path -LiteralPath $runtimeRoot -PathType Container) {
            $candidates += Get-ChildItem -LiteralPath $runtimeRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
        }
        $candidates += Join-Path $env:USERPROFILE ".sdkman\candidates\java\current\bin\java.exe"
    }
    $pathJava = Get-Command java -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $pathJava -and -not [string]::IsNullOrWhiteSpace($pathJava.Path)) {
        $candidates += $pathJava.Path
    }

    foreach ($candidate in @($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            continue
        }
        $versionOutput = @(& $candidate -version 2>&1)
        if ($LASTEXITCODE -ne 0) {
            continue
        }
        $versionText = $versionOutput -join "`n"
        if ($versionText -match 'version "([0-9]+)') {
            $major = [int]$Matches[1]
            if ($major -ge 21) {
                return $candidate
            }
        }
    }

    throw "Java 21 or newer is required. Install Java 21 or provision a runtime under %USERPROFILE%\.tailcat-mesh\runtime\java."
}

function Test-WindowsAdministrator {
    if ($env:OS -ne "Windows_NT") {
        return $false
    }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Assert-WindowsAdministrator {
    if ($env:OS -ne "Windows_NT") {
        throw "Tailcat Mesh Desktop Bootstrap requires Windows"
    }
    if (-not (Test-WindowsAdministrator)) {
        throw "Virtual LAN startup requires an elevated Windows process"
    }
}

function Get-AgentVirtualLanSettings {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigPath
    )

    $enabled = $false
    $interfaceName = "TailcatMesh"
    $adapterGuid = [Guid]::Parse("7d2a8db0-6f69-4d26-9d6e-9e0e4d2c6b71")
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        return [pscustomobject]@{ Enabled = $enabled; InterfaceName = $interfaceName; AdapterGuid = $adapterGuid }
    }

    $inVirtualLan = $false
    foreach ($line in Get-Content -LiteralPath $ConfigPath) {
        if ($line -match '^\s*virtualLan\s*:\s*(?:#.*)?$') {
            $inVirtualLan = $true
            continue
        }
        if ($inVirtualLan -and $line -match '^\S') {
            $inVirtualLan = $false
        }
        if (-not $inVirtualLan) {
            continue
        }
        if ($line -match '^\s+enabled\s*:\s*(true|false)\s*(?:#.*)?$') {
            $enabled = $Matches[1].ToLowerInvariant() -eq "true"
        } elseif ($line -match '^\s+interfaceName\s*:\s*(.+?)\s*(?:#.*)?$') {
            $interfaceName = $Matches[1].Trim().Trim('"', "'")
        } elseif ($line -match '^\s+adapterGuid\s*:\s*(.+?)\s*(?:#.*)?$') {
            try {
                $adapterGuid = [Guid]::Parse($Matches[1].Trim().Trim('"', "'"))
            } catch {
                throw "virtualLan.adapterGuid is invalid"
            }
        }
    }
    return [pscustomobject]@{ Enabled = $enabled; InterfaceName = $interfaceName; AdapterGuid = $adapterGuid }
}

function Resolve-AgentDataDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ConfigPath,
        [string]$DataDir
    )

    if (-not [string]::IsNullOrWhiteSpace($DataDir)) {
        return [IO.Path]::GetFullPath($DataDir)
    }
    $resolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path
    $configDirectory = Split-Path -Parent $resolvedConfig
    $configured = $null
    foreach ($line in Get-Content -LiteralPath $ConfigPath) {
        if ($line -match '^\s+dataDir\s*:\s*(.+?)\s*(?:#.*)?$') {
            $configured = $Matches[1].Trim().Trim('"', "'")
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($configured)) {
        if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
            throw "USERPROFILE is not set; cannot determine the Agent data directory"
        }
        return Join-Path $env:USERPROFILE ".tailcat-mesh\data\agent"
    }
    if ($configured -eq "~") {
        return [IO.Path]::GetFullPath($env:USERPROFILE)
    }
    if ($configured.StartsWith("~/") -or $configured.StartsWith("~\")) {
        $configured = Join-Path $env:USERPROFILE $configured.Substring(2)
    }
    if ([IO.Path]::IsPathRooted($configured)) {
        return [IO.Path]::GetFullPath($configured)
    }
    return [IO.Path]::GetFullPath((Join-Path $configDirectory $configured))
}

function Save-VerifiedZipEntry {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedSha256,
        [Parameter(Mandatory = $true)]
        [string]$EntryName,
        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw "Invalid SHA-256 pin for $EntryName"
    }
    if (Test-Path -LiteralPath $TargetPath -PathType Leaf) {
        return
    }

    $parent = Split-Path -Parent $TargetPath
    [void](New-Item -ItemType Directory -Path $parent -Force)
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("tailcat-mesh-" + [Guid]::NewGuid().ToString("N"))
    $archive = Join-Path $temporaryRoot "archive.zip"
    $extractRoot = Join-Path $temporaryRoot "extract"
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
    try {
        Invoke-WebRequest -Uri $Url -OutFile $archive -Headers @{
            "User-Agent" = "tailcat-mesh-desktop"
        } -ErrorAction Stop
        $actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        if (-not $actualSha256.Equals($ExpectedSha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw "SHA-256 mismatch for $EntryName; expected $ExpectedSha256 but got $actualSha256"
        }

        Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot -Force
        $source = Join-Path $extractRoot ($EntryName -replace '/', '\')
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Downloaded archive does not contain $EntryName"
        }
        $staged = Join-Path $temporaryRoot "payload"
        Copy-Item -LiteralPath $source -Destination $staged -Force
        if (Test-Path -LiteralPath $TargetPath -PathType Leaf) {
            return
        }
        try {
            [IO.File]::Move($staged, $TargetPath)
        } catch {
            if (-not (Test-Path -LiteralPath $TargetPath -PathType Leaf)) {
                throw
            }
        }
    } finally {
        try {
            [IO.Directory]::Delete($temporaryRoot, $true)
        } catch {
            # Temporary download cleanup is best effort.
        }
    }
}

function Ensure-VirtualLanDependencies {
    if ($env:OS -ne "Windows_NT") {
        throw "Virtual LAN dependencies are only available on Windows"
    }
    if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        throw "USERPROFILE is not set; cannot determine the ~/.tailcat-mesh cache"
    }

    $rawArchitecture = $env:PROCESSOR_ARCHITEW6432
    if ([string]::IsNullOrWhiteSpace($rawArchitecture)) {
        $rawArchitecture = $env:PROCESSOR_ARCHITECTURE
    }
    switch ($rawArchitecture.ToUpperInvariant()) {
        "AMD64" {
            $tun2SocksAsset = "tun2socks-windows-amd64-v3.zip"
            $tun2SocksSha256 = "ea9caa464664325afaaeded3e157a2152e29c5cfaa20a54157bcf330d6a93873"
            $wintunArchitecture = "amd64"
        }
        "ARM64" {
            $tun2SocksAsset = "tun2socks-windows-arm64.zip"
            $tun2SocksSha256 = "74497771068da13f42921adfc540f2abb9ac822404582c8cbe34d30e8c0ea1f5"
            $wintunArchitecture = "arm64"
        }
        default {
            throw "Unsupported Windows architecture for automatic Virtual LAN setup: $rawArchitecture"
        }
    }

    $cacheDirectory = Join-Path $env:USERPROFILE ".tailcat-mesh\virtual-lan\windows"
    $tun2SocksTarget = Join-Path $cacheDirectory "tun2socks.exe"
    $wintunTarget = Join-Path $cacheDirectory "wintun.dll"

    if (-not (Test-Path -LiteralPath $tun2SocksTarget -PathType Leaf)) {
        Write-Output "Preparing Virtual LAN tun2socks runtime..."
        Save-VerifiedZipEntry `
            -Url ("https://github.com/xjasonlyu/tun2socks/releases/download/v2.7.0/{0}" -f $tun2SocksAsset) `
            -ExpectedSha256 $tun2SocksSha256 `
            -EntryName ($tun2SocksAsset -replace '\.zip$', '.exe') `
            -TargetPath $tun2SocksTarget
    }

    if (-not (Test-Path -LiteralPath $wintunTarget -PathType Leaf)) {
        Write-Output "Preparing Virtual LAN Wintun runtime..."
        Save-VerifiedZipEntry `
            -Url "https://www.wintun.net/builds/wintun-0.14.1.zip" `
            -ExpectedSha256 "07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51" `
            -EntryName ("wintun/bin/{0}/wintun.dll" -f $wintunArchitecture) `
            -TargetPath $wintunTarget
    }
}

function Remove-StaleVirtualLanState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InterfaceName,
        [Parameter(Mandatory = $true)]
        [Guid]$AdapterGuid,
        [Parameter(Mandatory = $true)]
        [string]$AgentConfigPath
    )

    if ($env:OS -ne "Windows_NT") {
        return
    }

    $adapter = Get-NetAdapter -Name $InterfaceName -IncludeHidden -ErrorAction SilentlyContinue
    $expectedInstanceId = "SWD\Wintun\{" + $AdapterGuid.ToString().ToUpperInvariant() + "}"
    $device = @(Get-PnpDevice -Class Net -ErrorAction SilentlyContinue |
        Where-Object { ([string]$_.InstanceId).Equals($expectedInstanceId, [StringComparison]::OrdinalIgnoreCase) })
    if ($null -eq $adapter -and $device.Count -eq 0) {
        return
    }

    $resolvedConfigPath = (Resolve-Path -LiteralPath $AgentConfigPath).Path
    $javaProcesses = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @("java.exe", "javaw.exe") })
    $matchingAgent = @($javaProcesses | Where-Object {
        $commandLine = [string]$_.CommandLine
        -not [string]::IsNullOrWhiteSpace($commandLine) -and
            $commandLine.IndexOf($resolvedConfigPath, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    if ($matchingAgent.Count -gt 0) {
        throw "Agent for $InterfaceName is already running (PID $($matchingAgent[0].ProcessId))"
    }

    if ($null -ne $adapter) {
        $deviceId = [string]$adapter.DeviceID
        $normalizedDeviceId = ($deviceId -replace '[{}-]', '').ToUpperInvariant()
        $normalizedAdapterGuid = ($AdapterGuid.ToString() -replace '[{}-]', '').ToUpperInvariant()
        $guidMatches = -not [string]::IsNullOrWhiteSpace($deviceId) -and
            $normalizedDeviceId.Contains($normalizedAdapterGuid, [StringComparison]::OrdinalIgnoreCase)
        if (-not $guidMatches) {
            throw "Refusing to remove unexpected adapter '$InterfaceName' (DeviceID '$deviceId')"
        }
    }

    $meshJavaProcesses = @($javaProcesses | Where-Object {
        $commandLine = [string]$_.CommandLine
        -not [string]::IsNullOrWhiteSpace($commandLine) -and
            $commandLine.IndexOf("tailcat-mesh-agent", [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
    $sidecars = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ieq "tun2socks.exe" })
    foreach ($sidecar in $sidecars) {
        $commandLine = [string]$sidecar.CommandLine
        $matchesInterface = -not [string]::IsNullOrWhiteSpace($commandLine) -and
            ($commandLine.IndexOf($InterfaceName, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
             $commandLine.IndexOf($AdapterGuid.ToString("B"), [StringComparison]::OrdinalIgnoreCase) -ge 0)
        $parentExists = $false
        if ($sidecar.ParentProcessId -gt 0) {
            $parentExists = $null -ne (Get-Process -Id $sidecar.ParentProcessId -ErrorAction SilentlyContinue)
        }
        $orphanWithoutCommandLine = [string]::IsNullOrWhiteSpace($commandLine) -and
            $meshJavaProcesses.Count -eq 0 -and -not $parentExists -and $device.Count -gt 0
        if ($matchesInterface -or $orphanWithoutCommandLine) {
            Write-Output "Stopping stale Virtual LAN sidecar $($sidecar.ProcessId)..."
            Stop-Process -Id $sidecar.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }

    if ($device.Count -gt 0) {
        Write-Output "Removing stale Virtual LAN adapter $InterfaceName..."
        $removeOutput = @(& pnputil.exe /remove-device ([string]$device[0].InstanceId) 2>&1)
        $removeExitCode = $LASTEXITCODE
        if ($removeExitCode -ne 0) {
            throw "Could not remove stale Virtual LAN adapter '$InterfaceName': $($removeOutput -join ' ')"
        }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $remaining = Get-NetAdapter -Name $InterfaceName -IncludeHidden -ErrorAction SilentlyContinue
        $remainingDevice = @(Get-PnpDevice -Class Net -ErrorAction SilentlyContinue |
            Where-Object { ([string]$_.InstanceId).Equals($expectedInstanceId, [StringComparison]::OrdinalIgnoreCase) })
        if ($null -eq $remaining -and $remainingDevice.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "Stale Virtual LAN adapter '$InterfaceName' could not be removed"
}

function ConvertTo-ProcessArgument {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Invoke-ElevatedBootstrap {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("tailcat-mesh-bootstrap-" + [Guid]::NewGuid().ToString("N"))
    $stdoutPath = Join-Path $temporaryRoot "stdout.log"
    $stderrPath = Join-Path $temporaryRoot "stderr.log"
    [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
    try {
        $childArguments = @("-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", $ScriptPath) + $Arguments + @("-OutputFile", $stdoutPath, "-ErrorFile", $stderrPath)
        $argumentString = ($childArguments | ForEach-Object { ConvertTo-ProcessArgument ([string]$_) }) -join ' '
        $elevated = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argumentString -WorkingDirectory (Split-Path -Parent $ScriptPath) -Wait -PassThru
        if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) {
            Get-Content -LiteralPath $stdoutPath | ForEach-Object { [Console]::Out.WriteLine($_) }
        }
        if (Test-Path -LiteralPath $stderrPath -PathType Leaf) {
            Get-Content -LiteralPath $stderrPath | ForEach-Object { [Console]::Error.WriteLine($_) }
        }
        return [int]$elevated.ExitCode
    } finally {
        try {
            [IO.Directory]::Delete($temporaryRoot, $true)
        } catch {
            # Temporary elevation logs are best-effort cleanup.
        }
    }
}
