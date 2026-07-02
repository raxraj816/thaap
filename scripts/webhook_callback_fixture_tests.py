from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import uuid


FIXTURE_PATH = Path(__file__).with_name("webhook-callback-fixture.py")
SPEC = importlib.util.spec_from_file_location("webhook_callback_fixture", FIXTURE_PATH)
FIXTURE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(FIXTURE)


class WebhookCallbackFixtureTests(unittest.TestCase):

    def test_authenticated_event_id_uses_signed_payload_identity(self) -> None:
        event_id = str(uuid.uuid4())
        payload = json.dumps({"id": event_id}).encode("utf-8")

        self.assertEqual(
            event_id,
            FIXTURE.authenticated_event_id(payload, event_id),
        )

    def test_authenticated_event_id_rejects_changed_header(self) -> None:
        payload = json.dumps({"id": str(uuid.uuid4())}).encode("utf-8")

        with self.assertRaises(ValueError):
            FIXTURE.authenticated_event_id(payload, str(uuid.uuid4()))

    def test_authenticated_event_id_rejects_invalid_payload_identity(self) -> None:
        with self.assertRaises(ValueError):
            FIXTURE.authenticated_event_id(b'{"id":"not-a-uuid"}', "not-a-uuid")

    def test_durable_event_store_applies_one_business_effect_across_restart(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "events.sqlite3"
            event_id = str(uuid.uuid4())
            payload_sha256 = "a" * 64

            first_process = FIXTURE.DurableEventStore(database)
            self.assertTrue(first_process.apply_once(
                event_id, payload_sha256, "2026-07-02T00:00:00+00:00"))

            restarted_process = FIXTURE.DurableEventStore(database)
            self.assertFalse(restarted_process.apply_once(
                event_id, payload_sha256, "2026-07-02T00:01:00+00:00"))

    def test_durable_event_store_rejects_event_id_reuse_with_changed_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = FIXTURE.DurableEventStore(Path(directory) / "events.sqlite3")
            event_id = str(uuid.uuid4())
            store.apply_once(event_id, "a" * 64, "2026-07-02T00:00:00+00:00")

            with self.assertRaises(ValueError):
                store.apply_once(event_id, "b" * 64, "2026-07-02T00:01:00+00:00")

    def test_durable_event_store_serializes_concurrent_duplicate_delivery(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = FIXTURE.DurableEventStore(Path(directory) / "events.sqlite3")
            event_id = str(uuid.uuid4())

            with ThreadPoolExecutor(max_workers=8) as executor:
                results = list(executor.map(
                    lambda _: store.apply_once(
                        event_id,
                        "a" * 64,
                        "2026-07-02T00:00:00+00:00",
                    ),
                    range(16),
                ))

            self.assertEqual(1, sum(results))

    def test_running_fixture_acknowledges_duplicate_without_second_effect(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary_directory = Path(directory)
            secret = "whsec_fixture-test"
            secret_file = temporary_directory / "secret"
            output = temporary_directory / "evidence.jsonl"
            database = temporary_directory / "events.sqlite3"
            secret_file.write_text(secret, encoding="utf-8")
            event_id = str(uuid.uuid4())
            payload = json.dumps({"id": event_id}, separators=(",", ":")).encode("utf-8")
            timestamp = int(time.time())
            signature = hmac.new(
                secret.encode("utf-8"),
                str(timestamp).encode("ascii") + b"." + payload,
                hashlib.sha256,
            ).hexdigest()
            signature_header = f"t={timestamp},v1={signature}"
            port = available_port()
            process = subprocess.Popen(
                [
                    sys.executable,
                    str(FIXTURE_PATH),
                    "--port",
                    str(port),
                    "--secret-file",
                    str(secret_file),
                    "--output",
                    str(output),
                    "--dedup-db",
                    str(database),
                    "--fail-first",
                    "0",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            try:
                wait_until_healthy(process, port)
                statuses = [
                    post_signed_event(port, payload, event_id, signature_header),
                    post_signed_event(port, payload, event_id, signature_header),
                ]
            finally:
                process.terminate()
                process.wait(timeout=5)

            evidence = [
                json.loads(line)
                for line in output.read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual([204, 204], statuses)
            self.assertEqual([True, False], [
                item["businessEffectApplied"] for item in evidence
            ])
            self.assertEqual([False, True], [item["duplicate"] for item in evidence])


def available_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_until_healthy(process: subprocess.Popen[bytes], port: int) -> None:
    for _ in range(50):
        if process.poll() is not None:
            raise RuntimeError("Webhook callback fixture exited before becoming healthy")
        try:
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=0.2)
            connection.request("GET", "/health")
            response = connection.getresponse()
            response.read()
            connection.close()
            if response.status == 204:
                return
        except OSError:
            time.sleep(0.02)
    raise RuntimeError("Webhook callback fixture did not become healthy")


def post_signed_event(
    port: int,
    payload: bytes,
    event_id: str,
    signature_header: str,
) -> int:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
    connection.request(
        "POST",
        "/thaap",
        body=payload,
        headers={
            "Content-Type": "application/json",
            "THAAP-Delivery-Id": str(uuid.uuid4()),
            "THAAP-Event-Id": event_id,
            "THAAP-Signature": signature_header,
        },
    )
    response = connection.getresponse()
    response.read()
    connection.close()
    return response.status


if __name__ == "__main__":
    unittest.main()
