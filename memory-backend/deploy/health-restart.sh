#!/usr/bin/env bash
set -euo pipefail

container="jarvis-memory"
if ! /usr/bin/docker inspect "$container" >/dev/null 2>&1; then
  cd /opt/jarvis-memory/memory-backend
  /usr/bin/docker compose up --detach --build --remove-orphans
  exit 0
fi

state=$(/usr/bin/docker inspect --format '{{.State.Status}}' "$container")
if [[ "$state" != "running" ]]; then
  /usr/bin/docker start "$container" || {
    cd /opt/jarvis-memory/memory-backend
    /usr/bin/docker compose up --detach --build --remove-orphans
  }
  exit 0
fi

health=$(/usr/bin/docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")
if [[ "$health" == "unhealthy" ]]; then
  /usr/bin/docker restart "$container"
fi
