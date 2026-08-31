[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AgentJar,
    [Parameter(Mandatory = $true)]
    [string]$Config,
    [string]$Token,
    [string]$DataDir,
    [string]$OutputFile,
    [string]$ErrorFile
)

$ErrorActionPreference = "Stop"
$commonScript = Join-Path $PSScriptRoot "agent-common.ps1"
if (-not (Test-Path -LiteralPath $commonScript -PathType Leaf)) {
    throw "Tailcat Mesh Bootstrap helper not found: $commonScript"
}
. $commonScript

function Invoke-AgentProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaPath,
        [Parameter(Mandatory = $true)]
        [string[]]$JavaArguments
    )

    & $JavaPath @JavaArguments
    return [int]$LASTEXITCODE
}

try {
    $resolvedJar = (Resolve-Path -LiteralPath $AgentJar -ErrorAction Stop).Path
    $resolvedConfig = (Resolve-Path -LiteralPath $Config -ErrorAction Stop).Path
    $virtualLan = Get-AgentVirtualLanSettings -ConfigPath $resolvedConfig
    $resolvedDataDir = Resolve-AgentDataDirectory -ConfigPath $resolvedConfig -DataDir $DataDir

    if ($virtualLan.Enabled -and -not (Test-WindowsAdministrator)) {
        $childArguments = @(
            "-AgentJar", $resolvedJar,
            "-Config", $resolvedConfig,
            "-DataDir", $resolvedDataDir
        )
        if (-not [string]::IsNullOrWhiteSpace($Token)) {
            $childArguments += @("-Token", $Token)
        }
        $exitCode = Invoke-ElevatedBootstrap -ScriptPath $PSCommandPath -Arguments $childArguments
        exit $exitCode
    }

    if ($virtualLan.Enabled) {
        Assert-WindowsAdministrator
        Ensure-VirtualLanDependencies
        Remove-StaleVirtualLanState `
            -InterfaceName $virtualLan.InterfaceName `
            -AdapterGuid $virtualLan.AdapterGuid `
            -AgentConfigPath $resolvedConfig
    }

    $java = Resolve-Java21
    $statePath = Join-Path $resolvedDataDir "identity\agent-state.json"
    $javaArguments = @("-jar", $resolvedJar)
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
        $javaArguments += @("run", "--config", $resolvedConfig)
    } else {
        if ([string]::IsNullOrWhiteSpace($Token)) {
            throw "This device is not enrolled. Connect once with an enrollment token."
        }
        $javaArguments += @("connect", "--config", $resolvedConfig, "--token", $Token)
    }

    if (-not [string]::IsNullOrWhiteSpace($OutputFile)) {
        if ([string]::IsNullOrWhiteSpace($ErrorFile)) {
            throw "ErrorFile is required when OutputFile is used"
        }
        $outputParent = Split-Path -Parent $OutputFile
        $errorParent = Split-Path -Parent $ErrorFile
        [void](New-Item -ItemType Directory -Path $outputParent -Force)
        [void](New-Item -ItemType Directory -Path $errorParent -Force)
        $exitCode = Invoke-AgentProcess -JavaPath $java -JavaArguments $javaArguments 1> $OutputFile 2> $ErrorFile
    } else {
        $exitCode = Invoke-AgentProcess -JavaPath $java -JavaArguments $javaArguments
    }
    exit $exitCode
} catch {
    if (-not [string]::IsNullOrWhiteSpace($ErrorFile)) {
        $parent = Split-Path -Parent $ErrorFile
        [void](New-Item -ItemType Directory -Path $parent -Force)
        $_ | Out-File -LiteralPath $ErrorFile -Encoding utf8
    } else {
        [Console]::Error.WriteLine($_.Exception.Message)
    }
    exit 1
}
