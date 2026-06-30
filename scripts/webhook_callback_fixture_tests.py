import importlib.util
import json
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
