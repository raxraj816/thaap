from __future__ import annotations

import importlib.util
import json
import os
import pathlib
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name(
    "demo-chapter-20-processor-catalog.py"
)
SPEC = importlib.util.spec_from_file_location("chapter20_processor_catalog", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
chapter20 = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = chapter20
SPEC.loader.exec_module(chapter20)


class Chapter20ProcessorCatalogTests(unittest.TestCase):
    def test_catalog_covers_every_documented_partition_once(self) -> None:
        fixture_ids = [fixture.fixture_id for fixture in chapter20.FIXTURES]

        self.assertEqual(
            fixture_ids,
            [
                "approved",
                "issuer_decline",
                "insufficient_funds",
                "suspected_fraud",
                "cvc_failure",
                "avs_line1_failure",
                "avs_postal_failure",
                "three_ds_required",
                "unknown_default_approval",
            ],
        )
        self.assertEqual(len({fixture.card_number for fixture in chapter20.FIXTURES}), 9)

    def test_loopback_validation_rejects_remote_or_ambiguous_origins(self) -> None:
        self.assertEqual(
            chapter20.require_loopback_origin("http://127.0.0.1:8080/"),
            "http://127.0.0.1:8080",
        )
        self.assertEqual(
            chapter20.require_loopback_origin("https://[::1]:8443"),
            "https://[::1]:8443",
        )

        invalid = [
            "https://example.com",
            "http://127.0.0.1:8080/path",
            "http://user@localhost:8080",
            "http://localhost:8080?next=example.com",
            "file:///tmp/thaap",
        ]
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(chapter20.DemoFailure):
                    chapter20.require_loopback_origin(value)

    def test_api_client_rejects_every_redirect_without_forwarding_credentials(
        self,
    ) -> None:
        redirected_requests: list[dict[str, str | None]] = []

        class RedirectTargetHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                redirected_requests.append(
                    {
                        "apiKey": self.headers.get("X-Api-Key"),
                        "adminToken": self.headers.get(
                            "X-Admin-Bootstrap-Token"
                        ),
                    }
                )
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b"{}")

            def do_POST(self) -> None:
                self.do_GET()

            def log_message(self, format: str, *args: object) -> None:
                return

        target_server = ThreadingHTTPServer(
            ("127.0.0.1", 0), RedirectTargetHandler
        )

        class RedirectSourceHandler(BaseHTTPRequestHandler):
            redirect_code = 302

            def do_POST(self) -> None:
                self.send_response(type(self).redirect_code)
                self.send_header(
                    "Location",
                    f"http://127.0.0.1:{target_server.server_port}/capture",
                )
                self.end_headers()

            def log_message(self, format: str, *args: object) -> None:
                return

        source_server = ThreadingHTTPServer(
            ("127.0.0.1", 0), RedirectSourceHandler
        )
        target_thread = threading.Thread(
            target=target_server.serve_forever, daemon=True
        )
        source_thread = threading.Thread(
            target=source_server.serve_forever, daemon=True
        )
        target_thread.start()
        source_thread.start()

        try:
            client = chapter20.ApiClient(
                f"http://127.0.0.1:{source_server.server_port}"
            )
            for redirect_code in (301, 302, 303, 307, 308):
                with self.subTest(redirect_code=redirect_code):
                    RedirectSourceHandler.redirect_code = redirect_code
                    with self.assertRaisesRegex(
                        chapter20.DemoFailure,
                        "rejects redirects",
                    ):
                        client.post(
                            "/admin/merchants",
                            {"name": "redirect proof"},
                            api_key="api-secret",
                            admin_token="admin-secret",
                        )
                    self.assertEqual(redirected_requests, [])
        finally:
            source_server.shutdown()
            target_server.shutdown()
            source_server.server_close()
            target_server.server_close()
            source_thread.join()
            target_thread.join()

    def test_api_client_ignores_environment_proxy_without_forwarding_credentials(
        self,
    ) -> None:
        proxy_requests: list[dict[str, str | None]] = []
        direct_requests: list[str] = []

        class ProxyHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                proxy_requests.append(
                    {
                        "apiKey": self.headers.get("X-Api-Key"),
                        "adminToken": self.headers.get(
                            "X-Admin-Bootstrap-Token"
                        ),
                    }
                )
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b"{}")

            def log_message(self, format: str, *args: object) -> None:
                return

        proxy_server = ThreadingHTTPServer(("127.0.0.1", 0), ProxyHandler)

        class DirectHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                direct_requests.append(self.path)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}')

            def log_message(self, format: str, *args: object) -> None:
                return

        direct_server = ThreadingHTTPServer(("127.0.0.1", 0), DirectHandler)
        proxy_thread = threading.Thread(
            target=proxy_server.serve_forever, daemon=True
        )
        direct_thread = threading.Thread(
            target=direct_server.serve_forever, daemon=True
        )
        proxy_thread.start()
        direct_thread.start()
        proxy_url = f"http://127.0.0.1:{proxy_server.server_port}"
        proxy_environment = {
            "HTTP_PROXY": proxy_url,
            "http_proxy": proxy_url,
            "NO_PROXY": "",
            "no_proxy": "",
        }

        try:
            with mock.patch.dict(os.environ, proxy_environment), mock.patch.object(
                chapter20.urllib.request, "proxy_bypass", return_value=False
            ):
                client = chapter20.ApiClient(
                    f"http://127.0.0.1:{direct_server.server_port}"
                )
                response = client.get(
                    "/actuator/health",
                    api_key="api-secret",
                )
            self.assertEqual(response, {"status": "UP"})
            self.assertEqual(direct_requests, ["/actuator/health"])
            self.assertEqual(proxy_requests, [])
        finally:
            proxy_server.shutdown()
            direct_server.shutdown()
            proxy_server.server_close()
            direct_server.server_close()
            proxy_thread.join()
            direct_thread.join()

    def test_sensitive_report_scan_rejects_card_credentials_and_idempotency(self) -> None:
        benign = {"outcomes": [{"fixtureId": "approved"}]}
        chapter20.assert_report_is_sanitized(
            benign,
            api_key="thaap_test_secret",
            signing_secret="whsec_test_secret",
            customer_email="buyer@example.test",
            run_id="run-1",
        )

        prohibited_reports = [
            {"value": chapter20.FIXTURES[0].card_number},
            {"value": "thaap_test_secret"},
            {"value": "whsec_test_secret"},
            {"value": "buyer@example.test"},
            {"value": "chapter20-run-1-approved-payment-method"},
            {"value": "chapter20-run-1-approved-intent"},
            {"value": "chapter20-run-1-approved-confirm"},
        ]
        for report in prohibited_reports:
            with self.subTest(report=report):
                with self.assertRaises(chapter20.DemoFailure):
                    chapter20.assert_report_is_sanitized(
                        report,
                        api_key="thaap_test_secret",
                        signing_secret="whsec_test_secret",
                        customer_email="buyer@example.test",
                        run_id="run-1",
                    )

    def test_report_write_is_atomic_valid_json_and_owner_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "report.json"

            chapter20.write_atomic_json(output, {"fixtureCount": 9})

            self.assertEqual(json.loads(output.read_text()), {"fixtureCount": 9})
            self.assertEqual(
                stat.S_IMODE(output.stat().st_mode),
                0o600,
            )
            self.assertEqual(list(output.parent.glob("*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
