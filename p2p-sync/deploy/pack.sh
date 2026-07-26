#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:-}"
skip_build="${SKIP_BUILD:-0}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$(mvn -pl p2p-sync -q -DforceStdout help:evaluate -Dexpression=project.version | tr -d '\r' | tr -d '\n')"
if [[ -z "${version}" ]]; then
  echo "无法解析 p2p-sync 版本号" >&2
  exit 1
fi

if [[ "${skip_build}" != "1" ]]; then
  (cd "${repo_root}" && mvn -pl p2p-core,p2p-transfer,p2p-db -DskipTests=true install)
  (cd "${repo_root}" && mvn -pl p2p-sync -DskipTests=true package)
  (cd "${repo_root}" && mvn -pl p2p-sync -DskipTests=true dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=p2p-sync/target/dependency)
fi

jar="${repo_root}/p2p-sync/target/p2p-sync-${version}.jar"
deps_dir="${repo_root}/p2p-sync/target/dependency"
yaml_example="${repo_root}/p2p-sync/src/main/resources/p2p-sync.yaml.example"

if [[ ! -f "${jar}" ]]; then echo "找不到产物: ${jar}" >&2; exit 1; fi
if [[ ! -d "${deps_dir}" ]]; then echo "找不到依赖目录: ${deps_dir}" >&2; exit 1; fi
if [[ ! -f "${yaml_example}" ]]; then echo "找不到示例配置: ${yaml_example}" >&2; exit 1; fi

if [[ -z "${out_dir}" ]]; then
  out_dir="${repo_root}/dist"
fi

stamp="$(date +%Y%m%d%H%M%S)"
dist_root="${out_dir}/p2p-sync-${version}-${stamp}"

mkdir -p "${dist_root}/"{app,lib,conf,bin}
cp -f "${jar}" "${dist_root}/app/p2p-sync.jar"
cp -f "${deps_dir}/"*.jar "${dist_root}/lib/"
cp -f "${yaml_example}" "${dist_root}/conf/p2p-sync.yaml.example"

if [[ -f "${repo_root}/p2p-sync/deploy/run.sh" ]]; then cp -f "${repo_root}/p2p-sync/deploy/run.sh" "${dist_root}/bin/run.sh"; fi
if [[ -f "${repo_root}/p2p-sync/deploy/run.ps1" ]]; then cp -f "${repo_root}/p2p-sync/deploy/run.ps1" "${dist_root}/bin/run.ps1"; fi

echo "${dist_root}"
