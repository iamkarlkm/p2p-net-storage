param(
  [string]$Config = ""
)

$ErrorActionPreference = "Stop"

$Base = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($Config)) {
  $Config = (Join-Path $Base "conf\\p2p-sync.yaml")
}

$Cp = (Join-Path $Base "app\\p2p-sync.jar") + ";" + (Join-Path $Base "lib\\*")
& java -cp $Cp ("-Dp2p.sync.yaml=" + $Config) javax.net.p2p.filesync.app.P2PSyncNodeMain
