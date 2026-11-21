#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
sandbox="$(mktemp -d)"
trap 'rm -rf -- "$sandbox"' EXIT
export AID_SH_LIBRARY_MODE=1 AID_DATA_ROOT="$sandbox/data"
source "$repo/deploy/aid.sh"

mkdir -p "$DATA_ROOT/uploadPath"
calls="$sandbox/downloads"
database_query() {
  printf '%s\n' "$storage"
}

storage=cos
offer_official_assets_import none </dev/null > "$sandbox/message"
grep -q '手动上传资源包' "$sandbox/message"

storage=local
AID_ASSUME_YES=1 offer_official_assets_import none </dev/null > "$sandbox/message"
grep -q '安装已完成' "$sandbox/message"
offer_official_assets_import docker </dev/null > "$sandbox/message"
[[ ! -s "$sandbox/message" ]]

curl() {
  printf 'attempt\n' >> "$calls"
  return 22
}
if import_official_assets_locally; then
  printf 'failed download was unexpectedly reported as successful\n' >&2
  exit 1
fi
[[ "$(wc -l < "$calls")" -eq 1 ]]
[[ ! -e "$DATA_ROOT/uploadPath/aid" ]]
[[ -z "$(find "$DATA_ROOT/uploadPath" -maxdepth 1 -name '.official-assets.*' -print -quit)" ]]

mkdir -p "$DATA_ROOT/uploadPath/aid"
printf 'keep\n' > "$DATA_ROOT/uploadPath/aid/existing"
import_official_assets_locally
[[ "$(wc -l < "$calls")" -eq 1 ]]
grep -Fxq 'keep' "$DATA_ROOT/uploadPath/aid/existing"

printf 'official first-install asset tests passed\n'
