#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

for command in docker curl java node ffmpeg tesseract; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing required command: $command" >&2
    exit 1
  }
done

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env. Set SILICONFLOW_API_KEY and replace the example passwords, then run this script again."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

for variable in \
  DB_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD MINIO_SECRET_KEY QDRANT_API_KEY SILICONFLOW_API_KEY; do
  value="${!variable:-}"
  if [[ -z "$value" || "$value" == change-* ]]; then
    echo "Set a non-example value for $variable in .env" >&2
    exit 1
  fi
done

if [[ ! -d mysql/data/mysql && "${DB_USERNAME:-}" != "${MYSQL_APP_USER:-dovideo}" ]]; then
  echo "DB_USERNAME and MYSQL_APP_USER must match for a fresh database." >&2
  exit 1
fi

java_version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
java_major="${java_version%%.*}"
[[ "$java_major" == "1" ]] && java_major="$(cut -d. -f2 <<<"$java_version")"
node_major="$(node --version | sed 's/^v//' | cut -d. -f1)"
(( java_major >= 21 )) || { echo "JDK 21+ is required; found $java_version" >&2; exit 1; }
(( node_major >= 22 )) || { echo "Node.js 22+ is required; found $(node --version)" >&2; exit 1; }

docker info >/dev/null
docker compose --env-file .env config --quiet
docker compose --env-file .env up --wait --wait-timeout 120

curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  --header "api-key: ${QDRANT_API_KEY}" \
  http://127.0.0.1:6333/healthz >/dev/null
curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 1 \
  http://127.0.0.1:9000/minio/health/live >/dev/null

docker compose --env-file .env ps
echo

# ---- 应用层：后端 → 前端 → (可选) MCP 适配器，一键全起 ----
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home)}"

wait_http() { # $1=url $2=label
  for _ in $(seq 1 40); do
    sleep 3
    if curl -sf -m 3 -o /dev/null "$1"; then echo "✓ $2 就绪: $1"; return 0; fi
  done
  echo "✗ $2 启动超时: $1（看 /tmp/dovideo-*.log）" >&2; return 1
}

echo "==> 后端 (9090)"
(cd server && nohup ./mvnw -s .mvn/central-settings.xml spring-boot:run > /tmp/dovideo-backend.log 2>&1 &)
wait_http http://127.0.0.1:9090/health "后端"

echo "==> 前端 (5173)"
(cd client && nohup npm run dev > /tmp/dovideo-frontend.log 2>&1 &)
wait_http http://127.0.0.1:5173/ "前端"

if [[ -n "${MCP_CLIENT_TOKENS:-}" ]]; then
  echo "==> MCP 适配器 (9091)"
  # SERVER_PORT/SERVER_ADDRESS from .env target the backend; Spring's relaxed binding
  # would hijack the adapter onto 9090, so scrub them in this subshell.
  (cd mcp-server && unset SERVER_PORT SERVER_ADDRESS && MCP_SERVER_PORT=9091 \
    MCP_CLIENT_TOKENS="$MCP_CLIENT_TOKENS" \
    DOVIDEO_API_BASE="${DOVIDEO_API_BASE:-http://127.0.0.1:9090}" \
    DOVIDEO_API_TOKEN="${DOVIDEO_API_TOKEN:-}" \
    DOVIDEO_API_USERNAME="${DOVIDEO_API_USERNAME:-}" \
    DOVIDEO_API_PASSWORD="${DOVIDEO_API_PASSWORD:-}" \
    nohup ./mvnw -q -s .mvn/central-settings.xml spring-boot:run > /tmp/dovideo-mcp.log 2>&1 &)
  for _ in $(seq 1 40); do
    sleep 3
    code="$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:9091/mcp || true)"
    if [[ "$code" != "000" ]]; then echo "✓ MCP 适配器就绪 (GET /mcp → HTTP $code，POST 端点正常)"; break; fi
  done
else
  echo "==> MCP 适配器跳过（.env 未设置 MCP_CLIENT_TOKENS，见 .env.example）"
fi

echo
echo "全部就绪：前端 http://127.0.0.1:5173 · 后端 http://127.0.0.1:9090/health · 日志 /tmp/dovideo-*.log"
