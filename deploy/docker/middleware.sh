#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
credentials="$script_dir/.middleware.env"
project="${COMPOSE_PROJECT_NAME:-docker}"

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || fail 'Invalid COMPOSE_PROJECT_NAME.'

if [[ ! -e "$credentials" && ! -L "$credentials" ]]; then
  [[ "${1:-}" == up ]] || fail 'Run middleware.sh up to prepare the development configuration.'

  existing_volume="$(docker volume ls -q --filter "name=^${project}_aid-dev-mysql-data$")"
  if [[ -n "$existing_volume" && -z "${MYSQL_ROOT_PASSWORD:-}" ]]; then
    existing_mysql="$(docker ps -aq \
      --filter "label=com.docker.compose.project=$project" \
      --filter 'label=com.docker.compose.service=mysql-dev')"
    if [[ -n "$existing_mysql" && "$existing_mysql" != *$'\n'* ]]; then
      MYSQL_ROOT_PASSWORD="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$existing_mysql" \
        | sed -n 's/^MYSQL_ROOT_PASSWORD=//p')"
    fi
    [[ -n "${MYSQL_ROOT_PASSWORD:-}" ]] \
      || fail 'Existing MySQL volume has no recoverable configuration. Set MYSQL_ROOT_PASSWORD to its current value and retry.'
  fi

  MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(openssl rand -hex 24)}"
  TOKEN_SECRET="${TOKEN_SECRET:-$(openssl rand -base64 48)}"
  [[ -n "$MYSQL_ROOT_PASSWORD" && -n "$TOKEN_SECRET" ]] || fail 'Could not generate development credentials.'

  umask 077
  tmp="$(mktemp "$script_dir/.middleware.env.XXXXXX")"
  trap 'rm -f -- "$tmp"' EXIT
  printf 'export MYSQL_ROOT_PASSWORD=%q\nexport TOKEN_SECRET=%q\n' "$MYSQL_ROOT_PASSWORD" "$TOKEN_SECRET" > "$tmp"
  # Preserve the credentials selected by a concurrent startup.
  if ! ln -- "$tmp" "$credentials" 2>/dev/null && [[ ! -f "$credentials" ]]; then
    fail 'Could not persist development credentials.'
  fi
  rm -- "$tmp"
  trap - EXIT
fi

[[ -f "$credentials" && ! -L "$credentials" ]] || fail 'Expected a local .middleware.env file.'
chmod 600 "$credentials"
# shellcheck source=/dev/null
source "$credentials"
[[ -n "${MYSQL_ROOT_PASSWORD:-}" && -n "${TOKEN_SECRET:-}" ]] || fail 'Restore the missing values in .middleware.env.'

exec docker compose --project-directory "$script_dir" -p "$project" \
  -f "$script_dir/docker-compose.middleware.yml" "$@"
