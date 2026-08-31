[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Token
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jar = Join-Path $root "tailcat-mesh-agent\target\tailcat-mesh-agent-0.1.0-SNAPSHOT.jar"
$config = Join-Path $root "agent1.yml"
$state = Join-Path $root "data\agent\identity\agent-state.json"

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Agent JAR not found: $jar. Run Maven package first."
}
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) {
    throw "Agent config not found: $config"
}

$commonScript = Join-Path $PSScriptRoot "agent-common.ps1"
if (-not (Test-Path -LiteralPath $commonScript -PathType Leaf)) {
    throw "Agent helper script not found: $commonScript"
}
. $commonScript
Assert-WindowsAdministrator
$java = Resolve-Java21
Ensure-VirtualLanDependencies
Remove-StaleVirtualLanState `
    -InterfaceName "TailcatMeshAgent1" `
    -AdapterGuid "7d2a8db0-6f69-4d26-9d6e-9e0e4d2c6b71" `
    -AgentConfigPath $config
$javaArguments = @("-jar", $jar)
if (Test-Path -LiteralPath $state -PathType Leaf) {
    $javaArguments += @("run", "--config", $config)
} else {
    if ([string]::IsNullOrWhiteSpace($Token)) {
        Write-Error "Agent 1 is not enrolled. Run: .\agent1.ps1 -Token '<one-time-token>'"
        exit 2
    }
    $javaArguments += @("connect", "--config", $config, "--token", $Token)
}

Push-Location $root
try {
    & $java @javaArguments
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
