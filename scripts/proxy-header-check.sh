#!/usr/bin/env bash
set -euo pipefail
# Exercise the real nginx image against an echo origin over Docker sockets, never a product DB.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="thaap-proxy-proof-$$"
UI="${PROJECT}-ui"
ORIGIN="${PROJECT}-origin"
[[ -z "$(docker network ls -q --filter "name=^${PROJECT}$")" ]] || exit 1
for name in "$UI" "$ORIGIN"; do
    if docker container inspect "$name" >/dev/null 2>&1; then
        echo "FAIL: occupied proxy proof container name" >&2
        exit 1
    fi
done
cleanup() {
    docker rm -f "$UI" "$ORIGIN" >/dev/null 2>&1 || true
    docker network rm "$PROJECT" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create "$PROJECT" >/dev/null
docker run -d --name "$ORIGIN" --network "$PROJECT" --network-alias payments-api \
    python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4 python -c '
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
class Echo(BaseHTTPRequestHandler):
    def do_GET(self):
        payload=json.dumps({"path":self.path,"headers":{k.lower():v for k,v in self.headers.items()}}).encode()
        self.send_response(200); self.send_header("Content-Type","application/json"); self.end_headers(); self.wfile.write(payload)
    def log_message(self, *args): pass
HTTPServer(("0.0.0.0",8080),Echo).serve_forever()
' >/dev/null
docker run -d --name "$UI" --network "$PROJECT" thaap/analyst-dashboard-ui:local >/dev/null
response=""
for _ in $(seq 1 30); do
    if response="$(docker exec "$UI" wget -qO- \
        --header 'Forwarded: for=evil;host=evil.test;proto=https' \
        --header 'X-Forwarded-For: 1.2.3.4, ::1' \
        --header 'X-Forwarded-Host: evil.test' --header 'X-Forwarded-Port: 99' \
        --header 'X-Forwarded-Proto: https' --header 'X-Forwarded-Prefix: /evil' \
        --header 'X-Forwarded-Ssl: on' --header 'X-Api-Key: fixture-only' \
        --header 'Idempotency-Key: fixture-replay' \
        'http://127.0.0.1:8081/payment_intents/nested?filter=exact%2Fquery' 2>/dev/null)"; then break; fi
    sleep 1
done
printf '%s' "$response" | python3 -c '
import json,sys
response=json.load(sys.stdin); headers=response["headers"]
assert response["path"] == "/payment_intents/nested?filter=exact%2Fquery"
assert headers["x-forwarded-for"] == "127.0.0.1"
assert headers["x-real-ip"] == "127.0.0.1"
assert headers["x-forwarded-proto"] == "http"
assert headers["x-api-key"] == "fixture-only"
assert headers["idempotency-key"] == "fixture-replay"
assert not any(name in headers for name in ("forwarded","x-forwarded-host","x-forwarded-port","x-forwarded-prefix","x-forwarded-ssl"))
'
echo "PASS: real nginx strips forged proxy metadata and preserves credential/query bytes"
