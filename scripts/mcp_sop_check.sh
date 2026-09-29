#!/usr/bin/env bash
# One-shot MCP SOP acceptance: from a running stack to "external assistant got a
# grounded answer + a refusal through MCP". Exit 0 == SOP check passed.
#
# Usage: scripts/mcp_sop_check.sh [--ask-query "..."] [--refusal-query "..."]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  echo "SOP CHECK FAILED: .env missing (see docs/MCP_SOP.md §1)" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; source .env; set +a

MCP_URL="${MCP_URL:-http://127.0.0.1:9091/mcp}"
TOKEN="${MCP_CLIENT_TOKENS%%,*}"
if [[ -z "$TOKEN" ]]; then
  echo "SOP CHECK FAILED: MCP_CLIENT_TOKENS not set in .env" >&2
  exit 1
fi

echo "== [1/3] health gates =="
curl -sf -m 5 http://127.0.0.1:9090/health >/dev/null || {
  echo "SOP CHECK FAILED: backend :9090/health not reachable" >&2; exit 1; }
echo "  backend  :9090 UP"
curl -s -m 5 -o /dev/null -X POST "$MCP_URL" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' || true
code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$MCP_URL" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"ping"}')"
[[ "$code" == "200" ]] || {
  echo "SOP CHECK FAILED: mcp-server :9091 ping -> HTTP $code (expected 200)" >&2; exit 1; }
echo "  mcp-server :9091 UP (ping 200)"

echo "== [2/3] unauthenticated request must be rejected =="
code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X POST "$MCP_URL" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"ping"}')"
[[ "$code" == "401" ]] || {
  echo "SOP CHECK FAILED: tokenless ping -> HTTP $code (expected 401)" >&2; exit 1; }
echo "  bearer gate OK (401 without token)"

echo "== [3/3] full tool walkthrough =="
# Extra args after the flags are forwarded; defaults target the seeded corpus.
python3 scripts/mcp_e2e_client.py --url "$MCP_URL" --token "$TOKEN" "$@"

echo
echo "SOP CHECK PASSED"
