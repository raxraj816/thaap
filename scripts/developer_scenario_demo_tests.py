#!/usr/bin/env python3
"""Safety-contract tests for demo-developer-scenario-console.py."""

from __future__ import annotations

import importlib.util
import http.server
import os
import pathlib
import sys
import threading
import time
import unittest
from unittest import mock


SCRIPT = pathlib.Path(__file__).with_name("demo-developer-scenario-console.py")
SPEC = importlib.util.spec_from_file_location("demo_developer_scenario_console", SCRIPT)
assert SPEC and SPEC.loader
DEMO = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DEMO
SPEC.loader.exec_module(DEMO)


class DeveloperScenarioDemoSafetyTests(unittest.TestCase):

    def test_only_explicit_loopback_http_origins_are_accepted(self):
        self.assertEqual("http://127.0.0.1:8080", DEMO.require_loopback("http://127.0.0.1:8080"))
        self.assertEqual("http://[::1]:9090", DEMO.require_loopback("http://[::1]:9090/"))
        for origin in ("https://localhost:8080", "http://example.com:8080",
                       "http://localhost", "http://user@localhost:8080",
                       "http://localhost:8080?key=secret", "http://127.0.0.1:bad"):
            with self.subTest(origin=origin):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_loopback(origin)

    def test_step_path_grammar_rejects_redirect_and_ssrf_shapes(self):
        self.assertEqual("/customers", DEMO.require_step_path("/customers"))
        intent_id = "10000000-0000-0000-0000-000000000001"
        self.assertEqual(f"/payment_intents/{intent_id}/confirm",
                         DEMO.require_step_path(f"/payment_intents/{intent_id}/confirm"))
        for path in ("https://example.com/steal", "//example.com/steal", "/customers?key=x",
                     "/webhook_endpoints", "/payment_intents/not-a-uuid/confirm"):
            with self.subTest(path=path):
                with self.assertRaises(DEMO.DemoFailure):
                    DEMO.require_step_path(path)

    def test_client_disables_ambient_proxies_and_rejects_redirects(self):
        with mock.patch.dict(os.environ, {"HTTP_PROXY": "http://proxy.invalid:9999"}):
            client = DEMO.SafeClient("http://localhost:8080", "api", "runner")
        handler_types = {type(handler) for handler in client.opener.handlers}
        # build_opener omits an explicitly empty ProxyHandler from the final
        # chain; importantly, it does not install the ambient environment one.
        self.assertNotIn(urllib_proxy_handler(), handler_types)
        self.assertTrue(any(isinstance(handler, DEMO.RejectRedirectHandler)
                            for handler in client.opener.handlers))

    def test_missing_environment_credentials_fail_before_network_use(self):
        with self.assertRaises(DEMO.DemoFailure):
            DEMO.run_demo("http://localhost:8080", "", "", "safe-seed", "APPROVED_PAYMENT")

    def test_live_client_rejects_cross_origin_redirect_and_slow_response(self):
        redirect_server = LocalServer(RedirectHandler)
        with redirect_server:
            client = DEMO.SafeClient(redirect_server.origin, "api", "runner")
            with self.assertRaisesRegex(DEMO.DemoFailure, "rejects redirects"):
                client.request("GET", "/developer/test-cards", trusted=False)

        slow_server = LocalServer(SlowHandler)
        with slow_server:
            client = DEMO.SafeClient(slow_server.origin, "api", "runner", timeout_seconds=0.01)
            with self.assertRaisesRegex(DEMO.DemoFailure, "bounded client request"):
                client.request("GET", "/developer/test-cards", trusted=False)


def urllib_proxy_handler():
    import urllib.request
    return urllib.request.ProxyHandler


class LocalServer:
    def __init__(self, handler):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.origin = f"http://127.0.0.1:{self.server.server_port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exception_type, exception, traceback):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=1)


class QuietHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return


class RedirectHandler(QuietHandler):
    def do_GET(self):
        self.send_response(302)
        self.send_header("Location", "http://example.invalid/credential-capture")
        self.end_headers()


class SlowHandler(QuietHandler):
    def do_GET(self):
        time.sleep(0.05)
        try:
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"{}")
        except (BrokenPipeError, ConnectionResetError):
            return


if __name__ == "__main__":
    unittest.main()
