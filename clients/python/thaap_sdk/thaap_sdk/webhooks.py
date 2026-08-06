"""Exact-byte webhook verification and authenticated event deduplication."""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import threading
import time
import uuid
from dataclasses import dataclass, field


_SIGNATURE = re.compile(r"^t=(\d+),v1=([0-9a-f]{64})$")
_MAX_EVENT_BYTES = 65_536


class WebhookVerificationError(ValueError):
    pass


@dataclass
class InMemoryWebhookDeduplicator:
    """Sandbox deduplicator; production consumers should persist this mapping."""

    _events: dict[str, str] = field(default_factory=dict, init=False, repr=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, init=False, repr=False)

    def apply_once(self, event_id: str, payload: bytes) -> bool:
        try:
            uuid.UUID(event_id)
        except (AttributeError, TypeError, ValueError) as exception:
            raise WebhookVerificationError("Webhook deduplication requires a valid event ID") from exception
        if not isinstance(payload, bytes) or len(payload) > _MAX_EVENT_BYTES:
            raise WebhookVerificationError("Webhook payload exceeds the supported exact-byte boundary")
        payload_digest = hashlib.sha256(payload).hexdigest()
        with self._lock:
            existing = self._events.get(event_id)
            if existing is None:
                self._events[event_id] = payload_digest
                return True
            if not hmac.compare_digest(existing, payload_digest):
                raise WebhookVerificationError(
                    "Authenticated event ID was reused with different exact payload bytes"
                )
            return False


def verify_webhook(
    payload: bytes,
    signature_header: str,
    event_id_header: str,
    secret: str,
    *,
    tolerance_seconds: int = 300,
    now: int | None = None,
) -> dict[str, object]:
    if not isinstance(payload, bytes):
        raise TypeError("Webhook verification requires the exact request bytes")
    if len(payload) > _MAX_EVENT_BYTES:
        raise WebhookVerificationError("Webhook payload exceeds the supported exact-byte boundary")
    if not secret or len(secret) > 512 or any(character in "\r\n" for character in secret):
        raise WebhookVerificationError("Webhook secret is not a valid bounded value")
    match = _SIGNATURE.fullmatch(signature_header)
    if match is None or tolerance_seconds < 0 or tolerance_seconds > 3_600:
        raise WebhookVerificationError("Webhook signature header is invalid")
    timestamp = int(match.group(1))
    current = int(time.time()) if now is None else now
    if abs(current - timestamp) > tolerance_seconds:
        raise WebhookVerificationError("Webhook timestamp is outside the accepted window")
    signed = str(timestamp).encode("ascii") + b"." + payload
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, match.group(2)):
        raise WebhookVerificationError("Webhook signature did not match exact request bytes")
    try:
        document = json.loads(payload)
        payload_event_id = document["id"]
        uuid.UUID(payload_event_id)
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exception:
        raise WebhookVerificationError("Webhook payload has no valid authenticated event ID") from exception
    if not hmac.compare_digest(payload_event_id, event_id_header):
        raise WebhookVerificationError("Webhook event header does not match authenticated payload")
    return document
