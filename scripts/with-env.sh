#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE=${ENV_FILE:-"$REPO_ROOT/.env"}

if [ ! -f "$ENV_FILE" ]; then
  echo "Env file not found: $ENV_FILE" >&2
  echo "Create .env from .env.example or set ENV_FILE=/path/to/file" >&2
  exit 1
fi

if [ "$#" -eq 0 ]; then
  echo "Usage: scripts/with-env.sh <command> [args...]" >&2
  echo "Example: scripts/with-env.sh mvn spring-boot:run" >&2
  exit 1
fi

set -a
. "$ENV_FILE"
set +a

exec "$@"
