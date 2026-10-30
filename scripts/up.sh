#!/usr/bin/env bash
# Starts the compose stack with secrets generated on first use and kept outside the repo (mode 600).
# Reruns reuse them, so databases in existing volumes still accept their credentials.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${CLAIMPILOT_ENV_FILE:-$HOME/.config/claimpilot/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  {
    echo "CLAIMPILOT_DB_PASSWORD=$(openssl rand -hex 24)"
    echo "CLAIMPILOT_S3_ACCESS_KEY=cp$(openssl rand -hex 8)"
    echo "CLAIMPILOT_S3_SECRET_KEY=$(openssl rand -hex 24)"
    echo "CLAIMPILOT_PARTY_HMAC_KEY=$(openssl rand -hex 32)"
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
