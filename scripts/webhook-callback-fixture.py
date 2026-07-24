#!/usr/bin/env python3
"""Loopback-only Webhook delivery webhook receiver with signature verification."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import closing
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
    parser.add_argument("--dedup-db", type=Path)
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
    dedup_db = args.dedup_db or Path(f"{args.output}.dedup.sqlite3")
    event_store = DurableEventStore(dedup_db)
    state = FixtureRuntimeState()
    evidence_lock = threading.Lock()

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
            attempt_ordinal = state.next_attempt()
            response_status = 503 if attempt_ordinal <= args.fail_first else 204
            payload_sha256 = hashlib.sha256(payload).hexdigest()
            business_effect_applied = False
            duplicate = False
            if response_status == 204:
                try:
                    business_effect_applied = event_store.apply_once(
                        event_id,
                        payload_sha256,
                        datetime.now(timezone.utc).isoformat(),
                    )
                except ValueError:
                    self.send_error(409)
                    return
                duplicate = not business_effect_applied
            evidence = {
                "attemptOrdinal": attempt_ordinal,
                "businessEffectApplied": business_effect_applied,
                "deliveryId": self.headers.get("THAAP-Delivery-Id"),
                "duplicate": duplicate,
                "eventId": event_id,
                "payloadSha256": payload_sha256,
                "responseStatus": response_status,
                "signatureVerified": True,
                "receivedAt": datetime.now(timezone.utc).isoformat(),
            }
            with evidence_lock:
                with args.output.open("a", encoding="utf-8") as output:
                    output.write(json.dumps(evidence, sort_keys=True) + "\n")
                    output.flush()
                    os.fsync(output.fileno())
            self.send_response(response_status)
            self.end_headers()

        def log_message(self, format_string: str, *values: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.serve_forever()
    return 0


class FixtureRuntimeState:

    def __init__(self) -> None:
        self._attempt = 0
        self._lock = threading.Lock()

    def next_attempt(self) -> int:
        with self._lock:
            self._attempt += 1
            return self._attempt


class DurableEventStore:

    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        with closing(self._connect()) as connection:
            with connection:
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS processed_webhook_events (
                        event_id TEXT PRIMARY KEY,
                        payload_sha256 TEXT NOT NULL,
                        processed_at TEXT NOT NULL
                    )
                    """
                )

    def apply_once(
        self,
        event_id: str,
        payload_sha256: str,
        processed_at: str,
    ) -> bool:
        with closing(self._connect()) as connection:
            with connection:
                cursor = connection.execute(
                    """
                    INSERT INTO processed_webhook_events (
                        event_id,
                        payload_sha256,
                        processed_at
                    )
                    VALUES (?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    (event_id, payload_sha256, processed_at),
                )
                if cursor.rowcount == 1:
                    return True
                existing = connection.execute(
                    """
                    SELECT payload_sha256
                    FROM processed_webhook_events
                    WHERE event_id = ?
                    """,
                    (event_id,),
                ).fetchone()
                if existing is None:
                    raise RuntimeError("Webhook event deduplication state disappeared")
                if not hmac.compare_digest(existing[0], payload_sha256):
                    raise ValueError(
                        "Authenticated webhook event ID was reused with different payload bytes"
                    )
                return False

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=5.0)
        connection.execute("PRAGMA busy_timeout = 5000")
        return connection


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
