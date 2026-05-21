#!/usr/bin/env bash
set -euo pipefail

base="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
config="${1:-${base}/conf/p2p-sync.yaml}"

cp="${base}/app/p2p-sync.jar:${base}/lib/*"
exec java -cp "${cp}" -Dp2p.sync.yaml="${config}" javax.net.p2p.filesync.app.P2PSyncNodeMain
