#!/usr/bin/env python3
"""Safety tests for demo-webhook-operations.py."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import urllib.error
import urllib.request
import unittest


SCRIPT = pathlib.Path(__file__).with_name("demo-webhook-operations.py")
SPEC = importlib.util.spec_from_file_location("demo_webhook_operations", SCRIPT)
assert SPEC and SPEC.loader
DEMO = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DEMO
SPEC.loader.exec_module(DEMO)


class WebhookOperationsDemoSafetyTests(unittest.TestCase):

    def test_only_explicit_loopback_origins_are_accepted(self):
        self.assertEqual("http://127.0.0.1:8080", DEMO.require_loopback("http://127.0.0.1:8080"))
        for origin in ("https://localhost:8080", "http://example.com:8080", "http://localhost",
                       "http://user@localhost:8080", "http://localhost:8080?key=secret"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_missing_credentials_fail_before_network_use(self):
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.run_demo("http://127.0.0.1:8080", "", "")

    def test_receiver_echoes_only_exact_verification_and_records_event_bytes(self):
        with DEMO.Receiver() as receiver:
            token = "a" * 43
            verification = json.dumps({"type": "webhook.endpoint.verification",
                                       "challenge": token}).encode()
            request = urllib.request.Request(receiver.url, data=verification, method="POST")
            with urllib.request.urlopen(request, timeout=1) as response:
                self.assertEqual(204, response.status)
                self.assertEqual(token, response.headers["THAAP-Webhook-Verification"])
            event_bytes = b'{"id":"event-1","type":"payment_intent.created"}'
            event_request = urllib.request.Request(receiver.url, data=event_bytes, method="POST",
                headers={"THAAP-Event-Id": "event-1", "THAAP-Delivery-Id": "delivery-1",
                         "THAAP-Signature": "t=1,v1=" + "a" * 64})
            with self.assertRaises(urllib.error.HTTPError) as failure:
                urllib.request.urlopen(event_request, timeout=1)
            self.assertEqual(400, failure.exception.code)
            failure.exception.close()
            self.assertEqual(event_bytes, receiver.state.deliveries[0]["payload"])


if __name__ == "__main__":
    unittest.main()
