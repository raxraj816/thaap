#!/usr/bin/env python3
"""Loopback-only Chapter 19 webhook receiver with signature verification."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SIGNATURE_PATTERN = re.compile(r"^t=(\d+),v1=([0-9a-f]{64})$")
MAX_PAYLOAD_BYTES = 64 * 1024


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fail-first", type=int, default=1)
    parser.add_argument("--timestamp-tolerance-seconds", type=int, default=300)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.port < 1 or args.port > 65535:
        raise SystemExit("port must be between 1 and 65535")
    if args.fail_first < 0:
        raise SystemExit("fail-first must not be negative")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    state = {"attempt": 0}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path != "/health":
                self.send_error(404)
                return
            self.send_response(204)
            self.end_headers()

        def do_POST(self) -> None:
            if self.path != "/thaap":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_PAYLOAD_BYTES:
                self.send_error(413)
                return
            payload = self.rfile.read(length)
            verified = verify(
                args.secret_file,
                payload,
                self.headers.get("THAAP-Signature", ""),
                args.timestamp_tolerance_seconds,
            )
            if not verified:
                self.send_error(401)
                return
            try:
                event_id = authenticated_event_id(
                    payload, self.headers.get("THAAP-Event-Id", "")
                )
            except ValueError:
                self.send_error(400)
                return
            state["attempt"] += 1
            response_status = 503 if state["attempt"] <= args.fail_first else 204
            evidence = {
                "attemptOrdinal": state["attempt"],
                "deliveryId": self.headers.get("THAAP-Delivery-Id"),
                "eventId": event_id,
                "payloadSha256": hashlib.sha256(payload).hexdigest(),
                "responseStatus": response_status,
                "signatureVerified": True,
                "receivedAt": datetime.now(timezone.utc).isoformat(),
            }
            with args.output.open("a", encoding="utf-8") as output:
                output.write(json.dumps(evidence, sort_keys=True) + "\n")
            self.send_response(response_status)
            self.end_headers()

        def log_message(self, format_string: str, *values: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.serve_forever()
    return 0


def verify(secret_file: Path, payload: bytes, signature_header: str, tolerance: int) -> bool:
    if not secret_file.exists():
        return False
    match = SIGNATURE_PATTERN.fullmatch(signature_header)
    if match is None:
        return False
    timestamp = int(match.group(1))
    if abs(int(time.time()) - timestamp) > tolerance:
        return False
    secret = secret_file.read_text(encoding="utf-8").strip().encode("utf-8")
    signed_payload = str(timestamp).encode("ascii") + b"." + payload
    expected = hmac.new(secret, signed_payload, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, match.group(2))


def authenticated_event_id(payload: bytes, event_id_header: str) -> str:
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as exception:
        raise ValueError("Webhook payload is not valid UTF-8 JSON") from exception
    if not isinstance(document, dict) or not isinstance(document.get("id"), str):
        raise ValueError("Webhook payload has no event id")
    payload_event_id = document["id"]
    try:
        uuid.UUID(payload_event_id)
    except ValueError as exception:
        raise ValueError("Webhook payload event id is not a UUID") from exception
    if not hmac.compare_digest(payload_event_id, event_id_header):
        raise ValueError("Webhook event header does not match authenticated payload")
    return payload_event_id


if __name__ == "__main__":
    raise SystemExit(main())
