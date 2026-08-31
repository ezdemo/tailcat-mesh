[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Token
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$jar = Join-Path $root "tailcat-mesh-agent\target\tailcat-mesh-agent-0.1.0-SNAPSHOT.jar"
$config = Join-Path $root "agent2.yml"
$state = Join-Path $root "data\agent2\identity\agent-state.json"

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
    -InterfaceName "TailcatMeshAgent2" `
    -AdapterGuid "8e3b9ec1-7f7a-5e37-ae7f-af1f5e3d7c82" `
    -AgentConfigPath $config
$javaArguments = @("-jar", $jar)
if (Test-Path -LiteralPath $state -PathType Leaf) {
    $javaArguments += @("run", "--config", $config)
} else {
    if ([string]::IsNullOrWhiteSpace($Token)) {
        Write-Error "Agent 2 is not enrolled. Run: .\agent2.ps1 -Token '<one-time-token>'"
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
