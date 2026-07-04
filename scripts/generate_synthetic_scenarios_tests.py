#!/usr/bin/env python3
"""Unit tests for generate-synthetic-scenarios.py."""

from __future__ import annotations

import importlib.util
import datetime as dt
import os
import pathlib
import stat
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock


SCRIPT_PATH = pathlib.Path(__file__).with_name("generate-synthetic-scenarios.py")


def load_generator_module():
    spec = importlib.util.spec_from_file_location("generate_synthetic_scenarios", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


generator = load_generator_module()


class SyntheticScenarioGeneratorTests(unittest.TestCase):

    def test_same_seed_produces_same_public_scenario_plan(self):
        first_plan = generator.build_scenario_plan("chapter-16-demo")
        second_plan = generator.build_scenario_plan("chapter-16-demo")

        self.assertEqual(generator.plan_to_public_dict(first_plan), generator.plan_to_public_dict(second_plan))
        self.assertEqual(generator.plan_fingerprint(first_plan), generator.plan_fingerprint(second_plan))

    def test_scenario_labels_and_expected_outcomes_are_stable(self):
        plan = generator.build_scenario_plan("chapter-16-demo")
        public_plan = generator.plan_to_public_dict(plan)

        self.assertEqual([
            "happy_path_approved_authorization",
            "retryable_processor_decline",
            "basic_card_testing_pattern",
        ], [scenario["label"] for scenario in public_plan])
        self.assertEqual("APPROVED", public_plan[0]["payments"][0]["expectedProcessorOutcomeCode"])
        self.assertEqual("ISSUER_DECLINE", public_plan[1]["payments"][0]["expectedProcessorOutcomeCode"])
        self.assertEqual([
            "SUSPECTED_FRAUD",
            "CVC_FAILURE",
            "AVS_LINE1_FAILURE",
        ], [payment["expectedProcessorOutcomeCode"] for payment in public_plan[2]["payments"]])

    def test_generated_customer_phones_are_valid_deterministic_e164_values(self):
        first_plan = generator.build_scenario_plan("chapter-16-demo")
        second_plan = generator.build_scenario_plan("chapter-16-demo")

        self.assertEqual(
                [scenario.customer_phone for scenario in first_plan],
                [scenario.customer_phone for scenario in second_plan])
        for scenario in first_plan:
            self.assertRegex(scenario.customer_phone, r"^\+1[0-9]{10}$")

    def test_public_plan_contains_card_fixture_labels_not_raw_card_numbers(self):
        public_plan = generator.plan_to_public_dict(generator.build_scenario_plan("chapter-16-demo"))
        public_plan_text = repr(public_plan)

        self.assertIn("499999:0001", public_plan_text)
        self.assertNotIn("4999990000000001", public_plan_text)
        self.assertNotIn("4999990000000002", public_plan_text)
        self.assertNotIn("4999990000000004", public_plan_text)

    def test_sensitive_data_scan_fails_for_blocked_values_or_terms(self):
        summary = {
            "apiKey": "thaap_live_secret",
            "nested": {
                "card": "4999990000000001",
            },
        }

        result = generator.sensitive_data_scan(summary, ["thaap_live_secret", "4999990000000001"])

        self.assertEqual("FAIL", result["status"])
        self.assertGreaterEqual(len(result["findings"]), 2)

    def test_sensitive_data_scan_passes_for_safe_summary(self):
        summary = {
            "scenario": "happy_path_approved_authorization",
            "paymentAttemptId": "00000000-0000-0000-0000-000000000001",
            "processorOutcomeCode": "APPROVED",
        }

        result = generator.sensitive_data_scan(summary, ["4999990000000001", "secret_key"])

        self.assertEqual({"status": "PASS", "findings": []}, result)

    def test_non_local_or_credential_bearing_base_urls_are_rejected(self):
        self.assertEqual("http://localhost:8080",
                generator.validate_local_base_url("http://localhost:8080/"))
        self.assertEqual("https://127.0.0.1:8443",
                generator.validate_local_base_url("https://127.0.0.1:8443"))
        self.assertEqual("http://[::1]:8080",
                generator.validate_local_base_url("http://[::1]:8080"))

        for unsafe_url in (
                "https://payments.example.com",
                "http://user:secret@localhost:8080",
                "http://localhost:8080/api",
                "http://localhost:8080?token=secret",
        ):
            with self.subTest(unsafe_url=unsafe_url):
                with self.assertRaises(ValueError):
                    generator.validate_local_base_url(unsafe_url)

    def test_api_client_rejects_every_redirect_without_forwarding_credentials(self):
        redirected_requests: list[dict[str, str | None]] = []

        class RedirectTargetHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                redirected_requests.append({
                    "apiKey": self.headers.get("X-Api-Key"),
                    "adminToken": self.headers.get("X-Admin-Bootstrap-Token"),
                    "scenarioRunnerToken": self.headers.get(
                        "X-Thaap-Scenario-Runner-Token"),
                })
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b"{}")

            def do_POST(self) -> None:
                self.do_GET()

            def log_message(self, format: str, *args: object) -> None:
                return

        target_server = ThreadingHTTPServer(
                ("127.0.0.1", 0), RedirectTargetHandler)

        class RedirectSourceHandler(BaseHTTPRequestHandler):
            redirect_code = 302

            def do_POST(self) -> None:
                self.send_response(type(self).redirect_code)
                self.send_header(
                    "Location",
                    f"http://127.0.0.1:{target_server.server_port}/capture")
                self.end_headers()

            def log_message(self, format: str, *args: object) -> None:
                return

        source_server = ThreadingHTTPServer(
                ("127.0.0.1", 0), RedirectSourceHandler)
        target_thread = threading.Thread(
                target=target_server.serve_forever, daemon=True)
        source_thread = threading.Thread(
                target=source_server.serve_forever, daemon=True)
        target_thread.start()
        source_thread.start()

        try:
            client = generator.ApiClient(
                    f"http://127.0.0.1:{source_server.server_port}",
                    scenario_runner_token="runner-secret")
            for redirect_code in (301, 302, 303, 307, 308):
                with self.subTest(redirect_code=redirect_code):
                    RedirectSourceHandler.redirect_code = redirect_code
                    with self.assertRaisesRegex(
                            generator.UnsafeRedirectError,
                            "rejects redirects"):
                        client.post(
                                "/admin/merchants",
                                {"name": "redirect proof"},
                                headers={
                                    "X-Api-Key": "api-secret",
                                    "X-Admin-Bootstrap-Token": "admin-secret",
                                })
                    self.assertEqual([], redirected_requests)
        finally:
            source_server.shutdown()
            target_server.shutdown()
            source_server.server_close()
            target_server.server_close()
            source_thread.join()
            target_thread.join()

    def test_api_client_ignores_environment_proxy_without_forwarding_credentials(self):
        proxy_requests: list[dict[str, str | None]] = []
        direct_requests: list[str] = []

        class ProxyHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                proxy_requests.append({
                    "apiKey": self.headers.get("X-Api-Key"),
                    "adminToken": self.headers.get("X-Admin-Bootstrap-Token"),
                    "scenarioRunnerToken": self.headers.get(
                        "X-Thaap-Scenario-Runner-Token"),
                })
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
                target=proxy_server.serve_forever, daemon=True)
        direct_thread = threading.Thread(
                target=direct_server.serve_forever, daemon=True)
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
                    generator.urllib.request, "proxy_bypass", return_value=False):
                client = generator.ApiClient(
                        f"http://127.0.0.1:{direct_server.server_port}",
                        scenario_runner_token="runner-secret")
                status, body = client.get("/actuator/health", headers={
                    "X-Api-Key": "api-secret",
                    "X-Admin-Bootstrap-Token": "admin-secret",
                })
            self.assertEqual(200, status)
            self.assertEqual({"status": "UP"}, body)
            self.assertEqual(["/actuator/health"], direct_requests)
            self.assertEqual([], proxy_requests)
        finally:
            proxy_server.shutdown()
            direct_server.shutdown()
            proxy_server.server_close()
            direct_server.server_close()
            proxy_thread.join()
            direct_thread.join()

    def test_admin_token_is_read_from_environment_and_is_required(self):
        self.assertEqual("local-secret",
                generator.admin_token_from_environment({"THAAP_ADMIN_BOOTSTRAP_TOKEN": "local-secret"}))
        with self.assertRaises(ValueError):
            generator.admin_token_from_environment({})

    def test_scenario_runner_token_is_read_from_environment_and_is_required(self):
        self.assertEqual("runner-secret",
                generator.scenario_runner_token_from_environment(
                    {"THAAP_SCENARIO_RUNNER_TOKEN": "runner-secret"}))
        with self.assertRaises(ValueError):
            generator.scenario_runner_token_from_environment({})

    def test_run_ids_are_collision_resistant_even_at_the_same_instant(self):
        instant = dt.datetime(2026, 6, 27, 9, 30, 0, tzinfo=dt.timezone.utc)

        first_run_id = generator.new_run_id("chapter-16-demo", instant)
        second_run_id = generator.new_run_id("chapter-16-demo", instant)

        self.assertNotEqual(first_run_id, second_run_id)
        self.assertRegex(first_run_id, r"^20260627093000000000-[0-9a-f]{8}-[0-9a-f]{12}$")

    def test_non_empty_or_malformed_ledger_truth_fails_the_scenario(self):
        generator.assert_no_ledger_truth([], "decline")

        with self.assertRaisesRegex(RuntimeError, "unexpectedly created 1 ledger entries"):
            generator.assert_no_ledger_truth([{"id": "ledger-entry"}], "decline")
        with self.assertRaisesRegex(RuntimeError, "was not a list"):
            generator.assert_no_ledger_truth({"entries": []}, "decline")

    def test_summary_write_is_atomic_and_leaves_no_temporary_file(self):
        summary = {"runId": "run-1", "status": "PASS"}
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = pathlib.Path(temporary_directory) / "summary.json"

            written_path = generator.write_summary(summary, output)

            self.assertEqual(output, written_path)
            self.assertEqual('{\n  "runId": "run-1",\n  "status": "PASS"\n}\n', output.read_text())
            self.assertEqual([], list(pathlib.Path(temporary_directory).glob("*.tmp")))

    def test_run_store_replays_completed_step_without_duplicate_operation(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            store = generator.GeneratorRunStore(path)
            calls = []
            try:
                self.assertIsNone(store.acquire(
                    "run-1", "seed", "http://localhost:8080", "fingerprint"))

                first = store.execute_step(
                    "run-1", "merchant", False,
                    lambda: calls.append("called") or {"id": "merchant-1"})
                replay = store.execute_step(
                    "run-1", "merchant", False,
                    lambda: calls.append("duplicate") or {"id": "merchant-2"})

                self.assertEqual({"id": "merchant-1"}, first)
                self.assertEqual(first, replay)
                self.assertEqual(["called"], calls)
                self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
                self.assertEqual("delete", store.connection.execute(
                    "PRAGMA journal_mode").fetchone()[0])
                self.assertEqual(1, store.connection.execute(
                    "PRAGMA secure_delete").fetchone()[0])
                self.assertEqual([], list(path.parent.glob("runs.sqlite3-*")))
            finally:
                store.release("run-1")
                store.close()

    def test_interrupted_retry_safe_step_resumes_with_stable_checkpoint(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            first_store = generator.GeneratorRunStore(path)
            first_store.acquire("run-safe", "seed", "http://localhost:8080", "fingerprint")
            with self.assertRaisesRegex(RuntimeError, "ambiguous network result"):
                first_store.execute_step(
                    "run-safe", "payment-intent", True,
                    lambda: (_ for _ in ()).throw(RuntimeError("ambiguous network result")))
            first_store.release("run-safe")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                self.assertIsNone(second_store.acquire(
                    "run-safe", "seed", "http://localhost:8080", "fingerprint"))
                response = second_store.execute_step(
                    "run-safe", "payment-intent", True,
                    lambda: {"id": "pi_stable_replay"})
                self.assertEqual({"id": "pi_stable_replay"}, response)
            finally:
                second_store.release("run-safe")
                second_store.close()

    def test_crash_after_remote_commit_retries_only_a_retry_safe_step(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            remote_truth = {"paymentIntentIds": set()}
            first_store = generator.GeneratorRunStore(
                    path, fail_after_step="payment-intent")
            first_store.acquire(
                    "run-after-commit", "seed",
                    "http://localhost:8080", "fingerprint")

            def idempotent_remote_create():
                remote_truth["paymentIntentIds"].add("pi_stable")
                return {"id": "pi_stable"}

            with self.assertRaisesRegex(RuntimeError, "Injected generator crash"):
                first_store.execute_step(
                    "run-after-commit", "payment-intent", True,
                    idempotent_remote_create)
            first_store.release("run-after-commit")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                second_store.acquire(
                    "run-after-commit", "seed",
                    "http://localhost:8080", "fingerprint")
                response = second_store.execute_step(
                    "run-after-commit", "payment-intent", True,
                    idempotent_remote_create)
                self.assertEqual({"id": "pi_stable"}, response)
                self.assertEqual({"pi_stable"}, remote_truth["paymentIntentIds"])
            finally:
                second_store.release("run-after-commit")
                second_store.close()

    def test_ambiguous_non_idempotent_step_requires_explicit_abandonment(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            first_store = generator.GeneratorRunStore(path)
            first_store.acquire("run-unsafe", "seed", "http://localhost:8080", "fingerprint")
            first_store.execute_step(
                    "run-unsafe", "merchant", False,
                    lambda: {
                        "id": "merchant-retained",
                        "merchantId": "merchant-retained",
                        "apiKey": "secret-must-not-enter-resource-lineage",
                    })
            with self.assertRaises(RuntimeError):
                first_store.execute_step(
                    "run-unsafe", "customer", False,
                    lambda: (_ for _ in ()).throw(RuntimeError("connection closed")))
            first_store.release("run-unsafe")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                self.assertIsNone(second_store.acquire(
                    "run-unsafe", "seed", "http://localhost:8080", "fingerprint"))
                with self.assertRaises(generator.AmbiguousGeneratorStepError):
                    second_store.execute_step(
                        "run-unsafe", "customer", False,
                        lambda: self.fail("ambiguous merchant creation must not repeat"))
                second_store.abandon("run-unsafe", "retain-generated-resources")
                row = second_store.connection.execute("""
                        SELECT status, cleanup_disposition
                        FROM generator_runs
                        WHERE run_id = 'run-unsafe'
                        """).fetchone()
                self.assertEqual("ABANDONED", row["status"])
                self.assertEqual("retain-generated-resources", row["cleanup_disposition"])
                self.assertEqual([{
                    "stepKey": "merchant",
                    "resourceType": "MERCHANT",
                    "resourceId": "merchant-retained",
                    "merchantId": "merchant-retained",
                }], second_store.list_resource_references("run-unsafe"))
                resource_text = repr(
                        second_store.list_resource_references("run-unsafe"))
                self.assertNotIn("secret-must-not-enter-resource-lineage",
                        resource_text)
                checkpoint_count = second_store.connection.execute("""
                        SELECT COUNT(*)
                        FROM generator_steps
                        WHERE run_id = 'run-unsafe'
                          AND response_json IS NOT NULL
                        """).fetchone()[0]
                self.assertEqual(0, checkpoint_count)
            finally:
                second_store.close()

    def test_sanitized_resource_ledger_links_child_resources_to_their_tenant(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            store = generator.GeneratorRunStore(path)
            try:
                store.acquire(
                    "run-resource-lineage", "seed",
                    "http://localhost:8080", "fingerprint")
                store.execute_step(
                    "run-resource-lineage", "scenario.0.merchant", False,
                    lambda: {
                        "id": "merchant-safe",
                        "apiKey": "api-key-must-not-survive",
                    })
                store.execute_step(
                    "run-resource-lineage", "scenario.0.customer", False,
                    lambda: {
                        "id": "customer-safe",
                        "email": "pii-must-not-survive@example.test",
                    })
                store.abandon(
                    "run-resource-lineage", "retain-generated-resources")

                references = store.list_resource_references(
                    "run-resource-lineage")
                customer_reference = next(
                    reference for reference in references
                    if reference["resourceType"] == "CUSTOMER")
                self.assertEqual("merchant-safe",
                        customer_reference["merchantId"])
                self.assertEqual("customer-safe",
                        customer_reference["resourceId"])
                self.assertNotIn("api-key-must-not-survive", repr(references))
                self.assertNotIn("pii-must-not-survive", repr(references))
            finally:
                store.close()

    def test_active_run_lease_blocks_a_concurrent_generator(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            first_store = generator.GeneratorRunStore(path)
            second_store = generator.GeneratorRunStore(path)
            try:
                first_store.acquire(
                    "run-concurrent", "seed", "http://localhost:8080", "fingerprint")
                with self.assertRaisesRegex(RuntimeError, "active in another process"):
                    second_store.acquire(
                        "run-concurrent", "seed", "http://localhost:8080", "fingerprint")
            finally:
                first_store.release("run-concurrent")
                first_store.close()
                second_store.close()

    def test_completion_redacts_checkpoint_secrets_and_replays_safe_summary(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            store = generator.GeneratorRunStore(path)
            store.acquire("run-complete", "seed", "http://localhost:8080", "fingerprint")
            store.execute_step(
                "run-complete", "merchant", False,
                lambda: {"id": "merchant-1", "apiKey": "secret-value"})
            summary = {"runId": "run-complete", "status": "PASS"}
            store.complete("run-complete", summary)
            checkpoint = store.connection.execute("""
                    SELECT response_json
                    FROM generator_steps
                    WHERE run_id = 'run-complete' AND step_key = 'merchant'
                    """).fetchone()
            self.assertIsNone(checkpoint["response_json"])
            store.close()

            replay_store = generator.GeneratorRunStore(path)
            try:
                self.assertEqual(summary, replay_store.acquire(
                    "run-complete", "seed", "http://localhost:8080", "fingerprint"))
            finally:
                replay_store.close()


if __name__ == "__main__":
    unittest.main()
