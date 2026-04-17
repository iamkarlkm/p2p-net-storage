$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$protoDir = Join-Path $root "proto"
$outDir = Join-Path $root "gen\python"

New-Item -Force -ItemType Directory -Path $outDir | Out-Null

$protoc = Get-Command protoc -ErrorAction SilentlyContinue
if (-not $protoc) {
  throw "protoc not found in PATH"
}

& protoc "--proto_path=$protoDir" "--python_out=$outDir" (Join-Path $protoDir "p2p_wrapper.proto") (Join-Path $protoDir "p2p_control.proto")

