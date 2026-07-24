#!/usr/bin/env python3
"""Unit tests for generate-synthetic-scenarios.py."""

from __future__ import annotations

import importlib.util
import datetime as dt
import os
import pathlib
import sqlite3
import stat
import sys
import tempfile
import threading
import time
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
        first_plan = generator.build_scenario_plan("synthetic-scenario-demo")
        second_plan = generator.build_scenario_plan("synthetic-scenario-demo")

        self.assertEqual(generator.plan_to_public_dict(first_plan), generator.plan_to_public_dict(second_plan))
        self.assertEqual(generator.plan_fingerprint(first_plan), generator.plan_fingerprint(second_plan))

    def test_scenario_labels_and_expected_outcomes_are_stable(self):
        plan = generator.build_scenario_plan("synthetic-scenario-demo")
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
        first_plan = generator.build_scenario_plan("synthetic-scenario-demo")
        second_plan = generator.build_scenario_plan("synthetic-scenario-demo")

        self.assertEqual(
                [scenario.customer_phone for scenario in first_plan],
                [scenario.customer_phone for scenario in second_plan])
        for scenario in first_plan:
            self.assertRegex(scenario.customer_phone, r"^\+1[0-9]{10}$")

    def test_public_plan_contains_card_fixture_labels_not_raw_card_numbers(self):
        public_plan = generator.plan_to_public_dict(generator.build_scenario_plan("synthetic-scenario-demo"))
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

    def test_api_client_renews_run_ownership_across_slow_sequential_requests(self):
        class SlowHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                time.sleep(0.45)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}')

            def log_message(self, format: str, *args: object) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowHandler)
        server_thread = threading.Thread(
                target=server.serve_forever, daemon=True)
        server_thread.start()
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            owner = generator.GeneratorRunStore(path, lease_seconds=1)
            contender = generator.GeneratorRunStore(path, lease_seconds=1)
            try:
                owner.acquire(
                        "run-heartbeat", "seed",
                        f"http://127.0.0.1:{server.server_port}",
                        "fingerprint")
                client = generator.ApiClient(
                        f"http://127.0.0.1:{server.server_port}",
                        lease_heartbeat=lambda: owner.heartbeat("run-heartbeat"))

                for _ in range(3):
                    status, body = client.get("/slow")
                    self.assertEqual(200, status)
                    self.assertEqual({"status": "UP"}, body)

                with self.assertRaisesRegex(
                        RuntimeError, "active in another process"):
                    contender.acquire(
                            "run-heartbeat", "seed",
                            f"http://127.0.0.1:{server.server_port}",
                            "fingerprint")
            finally:
                owner.release("run-heartbeat")
                owner.close()
                contender.close()
                server.shutdown()
                server.server_close()
                server_thread.join()

    def test_generator_rejects_a_lease_that_cannot_cover_one_network_request(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            store = generator.GeneratorRunStore(
                    path,
                    lease_seconds=generator.ApiClient.REQUEST_TIMEOUT_SECONDS)
            try:
                with self.assertRaisesRegex(
                        ValueError, "must exceed the per-request network timeout"):
                    generator.run_generator(
                            "short-lease",
                            "http://127.0.0.1:8080",
                            "admin-token",
                            "runner-token",
                            "short-lease-run",
                            store)
                run_count = store.connection.execute(
                        "SELECT COUNT(*) FROM generator_runs").fetchone()[0]
                self.assertEqual(0, run_count)
            finally:
                store.close()

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

        first_run_id = generator.new_run_id("synthetic-scenario-demo", instant)
        second_run_id = generator.new_run_id("synthetic-scenario-demo", instant)

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
                    lambda: calls.append("called") or {"id": "merchant-1"},
                    resource_type="MERCHANT")
                replay = store.execute_step(
                    "run-1", "merchant", False,
                    lambda: calls.append("duplicate") or {"id": "merchant-2"},
                    resource_type="MERCHANT")

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
                    lambda: (_ for _ in ()).throw(RuntimeError("ambiguous network result")),
                    resource_type="PAYMENT_INTENT")
            first_store.release("run-safe")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                self.assertIsNone(second_store.acquire(
                    "run-safe", "seed", "http://localhost:8080", "fingerprint"))
                response = second_store.execute_step(
                    "run-safe", "payment-intent", True,
                    lambda: {
                        "id": "pi_stable_replay",
                        "merchantId": "merchant-safe",
                    },
                    resource_type="PAYMENT_INTENT")
                self.assertEqual({
                    "id": "pi_stable_replay",
                    "merchantId": "merchant-safe",
                }, response)
            finally:
                second_store.release("run-safe")
                second_store.close()

    def test_persisted_retry_classification_cannot_be_rewritten_on_resume(self):
        for persisted, resumed in ((False, True), (True, False)):
            with self.subTest(persisted=persisted, resumed=resumed):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    path = pathlib.Path(temporary_directory) / "runs.sqlite3"
                    first_store = generator.GeneratorRunStore(path)
                    first_store.acquire(
                            "run-classification", "seed",
                            "http://localhost:8080", "fingerprint")
                    with self.assertRaisesRegex(RuntimeError, "ambiguous"):
                        first_store.execute_step(
                                "run-classification", "merchant", persisted,
                                lambda: (_ for _ in ()).throw(
                                        RuntimeError("ambiguous")))
                    first_store.release("run-classification")
                    first_store.close()

                    second_store = generator.GeneratorRunStore(path)
                    calls = []
                    try:
                        second_store.acquire(
                                "run-classification", "seed",
                                "http://localhost:8080", "fingerprint")
                        with self.assertRaisesRegex(
                                RuntimeError, "retry classification changed"):
                            second_store.execute_step(
                                    "run-classification", "merchant", resumed,
                                    lambda: calls.append("must-not-run"))
                        self.assertEqual([], calls)
                        row = second_store.connection.execute("""
                                SELECT retry_safe
                                FROM generator_steps
                                WHERE run_id = ? AND step_key = ?
                                """, ("run-classification", "merchant")).fetchone()
                        self.assertEqual(int(persisted), row["retry_safe"])
                    finally:
                        second_store.release("run-classification")
                        second_store.close()

    def test_persisted_resource_type_cannot_be_rewritten_on_resume(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            first_store = generator.GeneratorRunStore(path)
            first_store.acquire(
                    "run-resource-type", "seed",
                    "http://localhost:8080", "fingerprint")
            with self.assertRaisesRegex(RuntimeError, "ambiguous"):
                first_store.execute_step(
                        "run-resource-type", "typed-step", True,
                        lambda: (_ for _ in ()).throw(RuntimeError("ambiguous")),
                        resource_type="MERCHANT")
            first_store.release("run-resource-type")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            calls = []
            try:
                second_store.acquire(
                        "run-resource-type", "seed",
                        "http://localhost:8080", "fingerprint")
                with self.assertRaisesRegex(
                        RuntimeError, "resource type changed"):
                    second_store.execute_step(
                            "run-resource-type", "typed-step", True,
                            lambda: calls.append("must-not-run"),
                            resource_type="CUSTOMER")
                self.assertEqual([], calls)
                row = second_store.connection.execute("""
                        SELECT resource_type
                        FROM generator_steps
                        WHERE run_id = ? AND step_key = ?
                        """, ("run-resource-type", "typed-step")).fetchone()
                self.assertEqual("MERCHANT", row["resource_type"])
            finally:
                second_store.release("run-resource-type")
                second_store.close()

    def test_legacy_journal_backfills_typed_completed_and_started_steps(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "legacy-runs.sqlite3"
            connection = sqlite3.connect(path)
            connection.executescript("""
                    CREATE TABLE generator_runs (
                        run_id TEXT PRIMARY KEY,
                        seed TEXT NOT NULL,
                        base_url TEXT NOT NULL,
                        plan_fingerprint TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lease_owner TEXT,
                        lease_expires_at REAL,
                        cleanup_disposition TEXT,
                        summary_json TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    );
                    CREATE TABLE generator_steps (
                        run_id TEXT NOT NULL,
                        step_key TEXT NOT NULL,
                        status TEXT NOT NULL,
                        retry_safe INTEGER NOT NULL,
                        response_json TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (run_id, step_key)
                    );
                    INSERT INTO generator_runs (
                        run_id, seed, base_url, plan_fingerprint, status,
                        created_at, updated_at
                    ) VALUES (
                        'legacy-run', 'seed', 'http://localhost:8080',
                        'fingerprint', 'RUNNING', 'now', 'now'
                    );
                    INSERT INTO generator_steps (
                        run_id, step_key, status, retry_safe,
                        response_json, updated_at
                    ) VALUES (
                        'legacy-run', 'scenario.0.merchant', 'COMPLETED', 1,
                        '{"id":"legacy-merchant","apiKey":"must-not-enter-ledger"}',
                        'now'
                    );
                    INSERT INTO generator_steps (
                        run_id, step_key, status, retry_safe,
                        response_json, updated_at
                    ) VALUES (
                        'legacy-run', 'tenant_isolation.verifier_merchant',
                        'STARTED', 1, NULL, 'now'
                    );
                    """)
            connection.commit()
            connection.close()

            store = generator.GeneratorRunStore(path)
            try:
                step_types = store.connection.execute("""
                        SELECT step_key, resource_type
                        FROM generator_steps
                        ORDER BY step_key
                        """).fetchall()
                self.assertEqual([
                    ("scenario.0.merchant", "MERCHANT"),
                    ("tenant_isolation.verifier_merchant", "MERCHANT"),
                ], [(row["step_key"], row["resource_type"]) for row in step_types])
                references = store.list_resource_references("legacy-run")
                self.assertEqual("legacy-merchant", references[0]["resourceId"])
                self.assertNotIn("must-not-enter-ledger", repr(references))
            finally:
                store.close()

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
                return {
                    "id": "pi_stable",
                    "merchantId": "merchant-stable",
                }

            with self.assertRaisesRegex(RuntimeError, "Injected generator crash"):
                first_store.execute_step(
                    "run-after-commit", "payment-intent", True,
                    idempotent_remote_create,
                    resource_type="PAYMENT_INTENT")
            first_store.release("run-after-commit")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                second_store.acquire(
                    "run-after-commit", "seed",
                    "http://localhost:8080", "fingerprint")
                response = second_store.execute_step(
                    "run-after-commit", "payment-intent", True,
                    idempotent_remote_create,
                    resource_type="PAYMENT_INTENT")
                self.assertEqual({
                    "id": "pi_stable",
                    "merchantId": "merchant-stable",
                }, response)
                self.assertEqual({"pi_stable"}, remote_truth["paymentIntentIds"])
            finally:
                second_store.release("run-after-commit")
                second_store.close()

    def test_merchant_and_customer_remote_commits_are_reconciled_before_local_checkpoint(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = pathlib.Path(temporary_directory) / "runs.sqlite3"
            remote_resources = {}

            def remote_create(resource_type, idempotency_key, response):
                return remote_resources.setdefault(
                        (resource_type, idempotency_key), response)

            for step_key, response in (
                    ("scenario.0.merchant", {
                        "id": "merchant-reconciled",
                        "apiKey": "thaap_test_reconciled",
                    }),
                    ("scenario.0.customer", {
                        "id": "customer-reconciled",
                        "merchantId": "merchant-reconciled",
                    }),
            ):
                resource_type = (
                    "MERCHANT" if step_key.endswith(".merchant") else "CUSTOMER")
                idempotency_key = f"stable-{step_key}"
                first_store = generator.GeneratorRunStore(
                        path, fail_after_step=step_key)
                first_store.acquire(
                        "run-resource-reconciliation", "seed",
                        "http://localhost:8080", "fingerprint")
                with self.assertRaisesRegex(RuntimeError, "Injected generator crash"):
                    first_store.execute_step(
                            "run-resource-reconciliation", step_key, True,
                            lambda resource_type=step_key, key=idempotency_key,
                            body=response: remote_create(resource_type, key, body),
                            resource_type=resource_type)
                self.assertNotIn(
                        response["id"],
                        repr(first_store.list_resource_references(
                                "run-resource-reconciliation")))
                first_store.release("run-resource-reconciliation")
                first_store.close()

                second_store = generator.GeneratorRunStore(path)
                second_store.acquire(
                        "run-resource-reconciliation", "seed",
                        "http://localhost:8080", "fingerprint")
                replay = second_store.execute_step(
                        "run-resource-reconciliation", step_key, True,
                        lambda resource_type=step_key, key=idempotency_key,
                        body=response: remote_create(resource_type, key, body),
                        resource_type=resource_type)
                self.assertEqual(response, replay)
                self.assertIn(
                        response["id"],
                        repr(second_store.list_resource_references(
                                "run-resource-reconciliation")))
                second_store.release("run-resource-reconciliation")
                second_store.close()

            self.assertEqual(2, len(remote_resources))

    def test_merchant_and_customer_requests_send_stable_domain_separated_idempotency_keys(self):
        class RecordingClient:
            def __init__(self):
                self.requests = []

            def post(self, path, body, headers, expected_status):
                self.requests.append((path, body, headers, expected_status))
                return expected_status, {"id": f"resource-{len(self.requests)}"}

        client = RecordingClient()

        generator.create_merchant(
                client, "admin-token", "Merchant", "stable-seed", 2)
        generator.create_customer(
                client, "api-key", "buyer@example.test", "+15555550123",
                "stable-seed", 2)

        merchant_key = client.requests[0][2]["Idempotency-Key"]
        customer_key = client.requests[1][2]["Idempotency-Key"]
        self.assertEqual(
                generator.idempotency_key(
                        "stable-seed", 2, 0, "merchant"),
                merchant_key)
        self.assertEqual(
                generator.idempotency_key(
                        "stable-seed", 2, 0, "customer"),
                customer_key)
        self.assertNotEqual(merchant_key, customer_key)

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
                    },
                    resource_type="MERCHANT")
            with self.assertRaises(RuntimeError):
                first_store.execute_step(
                    "run-unsafe", "customer", False,
                    lambda: (_ for _ in ()).throw(RuntimeError("connection closed")),
                    resource_type="CUSTOMER")
            first_store.release("run-unsafe")
            first_store.close()

            second_store = generator.GeneratorRunStore(path)
            try:
                self.assertIsNone(second_store.acquire(
                    "run-unsafe", "seed", "http://localhost:8080", "fingerprint"))
                with self.assertRaises(generator.AmbiguousGeneratorStepError):
                    second_store.execute_step(
                        "run-unsafe", "customer", False,
                        lambda: self.fail("ambiguous merchant creation must not repeat"),
                        resource_type="CUSTOMER")
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
                    },
                    resource_type="MERCHANT")
                store.execute_step(
                    "run-resource-lineage", "scenario.0.customer", False,
                    lambda: {
                        "id": "customer-safe",
                        "merchantId": "merchant-safe",
                        "email": "pii-must-not-survive@example.test",
                    },
                    resource_type="CUSTOMER")
                store.execute_step(
                    "run-resource-lineage", "tenant_isolation.verifier_merchant",
                    True,
                    lambda: {
                        "id": "verifier-merchant-safe",
                        "apiKey": "verifier-api-key-must-not-survive",
                    },
                    resource_type="MERCHANT")
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
                self.assertIn("verifier-merchant-safe", {
                    reference["resourceId"] for reference in references
                })
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
                lambda: {"id": "merchant-1", "apiKey": "secret-value"},
                resource_type="MERCHANT")
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
