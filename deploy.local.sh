#!/usr/bin/env bash
set -euo pipefail

ROOT=/opt/bank-lab
SOURCE="${GITHUB_WORKSPACE:?Missing GITHUB_WORKSPACE}"
SHA="${GITHUB_SHA:?Missing GITHUB_SHA}"
RELEASE="$ROOT/releases/backend-$SHA"

# Shared lock used by both repositories.
exec 9>"$ROOT/deploy.lock"
flock -w 3600 9

mkdir -p "$RELEASE"
git -C "$SOURCE" archive "$SHA" | tar -x -C "$RELEASE"

export RELEASE

python3 - <<'PY'
import os
from pathlib import Path
import yaml

release = Path(os.environ["RELEASE"])
source = release / "docker-compose.yml"

with source.open() as f:
    cfg = yaml.safe_load(f)

services = cfg["services"]

# Only frontend and authentication need host ports.
for service in services.values():
    service.pop("ports", None)
    service["restart"] = "unless-stopped"

mysql = services["mysql"]
mysql["environment"] = {
    "MYSQL_ROOT_PASSWORD": "${MYSQL_ROOT_PASSWORD}"
}
mysql["volumes"] = ["mysql-data:/var/lib/mysql"]
mysql["healthcheck"] = {
    "test": [
        "CMD-SHELL",
        'MYSQL_PWD="$${MYSQL_ROOT_PASSWORD}" '
        "mysqladmin ping -h localhost --silent"
    ],
    "interval": "10s",
    "timeout": "5s",
    "retries": 30
}

auth = services["keycloak"]
auth["ports"] = ["${LAB_HOST}:8080:8080"]
auth["command"] = [
    "start-dev",
    "--import-realm",
    "--http-port=8080"
]
auth["environment"] = {
    "KEYCLOAK_ADMIN": "${KEYCLOAK_ADMIN}",
    "KEYCLOAK_ADMIN_PASSWORD": "${KEYCLOAK_ADMIN_PASSWORD}",
    "KC_HOSTNAME": "http://${LAB_HOST}:8080",
    "KC_HOSTNAME_STRICT": "false"
}
auth["volumes"] = [
    "./keycloak/realm-digital-bank.json:"
    "/opt/keycloak/data/import/realm-digital-bank.json:ro",
    "./keycloak/themes:/opt/keycloak/themes:ro",
    "keycloak-data:/opt/keycloak/data"
]
auth["healthcheck"] = {
    "test": [
        "CMD", "bash", "-c",
        "exec 3<>/dev/tcp/127.0.0.1/8080"
    ],
    "interval": "10s",
    "timeout": "5s",
    "retries": 30
}

issuer = "http://${LAB_HOST}:8080/realms/digital-bank"
jwks = (
    "http://keycloak:8080/realms/digital-bank/"
    "protocol/openid-connect/certs"
)

for name in [
    "customer-service",
    "account-service",
    "transaction-service",
    "payment-service"
]:
    env = services[name].setdefault("environment", {})
    env.update({
        "DB_HOST": "mysql",
        "DB_PORT": "3306",
        "DB_USERNAME": "root",
        "DB_PASSWORD": "${MYSQL_ROOT_PASSWORD}",
        "SANDBOX_ISSUER_URI": issuer,
        "SANDBOX_JWKS_URI": jwks,
        "MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS": "never"
    })

gateway = services["gateway"].setdefault("environment", {})
gateway.update({
    "SANDBOX_ISSUER_URI": issuer,
    "SANDBOX_JWKS_URI": jwks,
    "SPRING_APPLICATION_JSON":
        '{"sandbox":{"gateway":{"api-keys":'
        '["${SANDBOX_API_KEY}"]}}}',
    "MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS": "never"
})

if "ai-assistant-service" in services:
    ai = services["ai-assistant-service"].setdefault(
        "environment", {}
    )
    ai.update({
        "SANDBOX_ISSUER_URI": issuer,
        "SANDBOX_JWKS_URI": jwks,
        "AI_GATEWAY_BASE_URL": "http://gateway:8081/api/v1",
        "AI_SANDBOX_KEY": "${SANDBOX_API_KEY}",
        "AI_TRANSFERS_ENABLED": "false",
        "AI_CONFIRMATION_SECRET": "${AI_CONFIRMATION_SECRET}",
        "OPENAI_API_KEY": "${OPENAI_API_KEY:-}",
        "MANAGEMENT_ENDPOINT_HEALTH_SHOWDETAILS": "never"
    })

cfg["volumes"] = {
    "mysql-data": {},
    "keycloak-data": {}
}
cfg["networks"] = {
    "default": {"name": "bank-lab"}
}

with (release / "compose.local.yml").open("w") as f:
    yaml.safe_dump(cfg, f, sort_keys=False)
PY

cd "$RELEASE"

compose() {
  docker compose \
    --project-name bank-backend \
    --env-file "$ROOT/secrets/backend.env" \
    -f compose.local.yml \
    "$@"
}

compose config --quiet

# Limit concurrent builds to reduce laptop memory pressure.
COMPOSE_PARALLEL_LIMIT=1 compose build

compose up -d --no-build --wait --wait-timeout 300

# Verify application HTTP readiness from inside the network.
for service in \
  customer-service:8082 \
  account-service:8083 \
  transaction-service:8084 \
  payment-service:8085 \
  gateway:8081
do
  docker run --rm --network bank-lab \
    curlimages/curl:8.12.1 \
    --fail --silent --show-error \
    --retry 30 --retry-all-errors --retry-delay 5 \
    --max-time 10 \
    "http://$service/actuator/health"
done

printf '%s\n' "$SHA" > "$ROOT/backend-current"
echo "Backend deployment completed."
