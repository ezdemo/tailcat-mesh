# Compatibility shim: development Agent1/Agent2 scripts use the same helper
# implementation that is shipped with the Desktop installer.
$desktopBootstrapCommon = Join-Path $PSScriptRoot "resources\bootstrap\agent-common.ps1"
if (-not (Test-Path -LiteralPath $desktopBootstrapCommon -PathType Leaf)) {
    throw "Tailcat Mesh Bootstrap helper not found: $desktopBootstrapCommon"
}
. $desktopBootstrapCommon
