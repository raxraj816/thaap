"""Release boundary partitions and proof ownership regression tests (no AWS mutation)."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("routing_contract", ROOT / "scripts/routing-contract-check.py")
ROUTING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ROUTING)


class ReleaseBoundaryTests(unittest.TestCase):
    def test_ci_executes_default_runtime_journeys_and_socket_proofs(self):
        workflow = (ROOT / ".github/workflows/commit-readiness.yml").read_text()
        self.assertIn('THAAP_STACK_CHECK_JOURNEYS: "true"', workflow)
        for script in ("compose-stack-check", "broker-persistence-check", "proxy-header-check"):
            self.assertIn(f"bash scripts/{script}.sh", workflow)
        stack = (ROOT / "scripts/compose-stack-check.sh").read_text()
        for journey in ("feature-lineage", "fraud-decision-loop", "governed-controls",
                        "payment-investigation", "saved-views-and-bulk-review", "workspace-insights"):
            self.assertIn(journey, stack)
        self.assertIn("demo-processor-outcome-catalog.py", stack)
    def test_existing_proof_namespace_is_refused_without_teardown(self):
        for occupied_kind in ("container", "volume", "network"):
            with self.subTest(resource=occupied_kind), tempfile.TemporaryDirectory() as directory:
                binary = pathlib.Path(directory) / "docker"
                calls = pathlib.Path(directory) / "calls"
                binary.write_text("""#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_DOCKER_CALLS"
case "$1" in
  ps) kind=container ;;
  volume) kind=volume ;;
  network) kind=network ;;
  *) echo 'unexpected Docker mutation' >&2; exit 90 ;;
esac
if [[ "$kind" == "$FAKE_OCCUPIED_KIND" ]]; then echo existing-owned-resource; fi
""")
                binary.chmod(0o755)
                environment = {**os.environ, "PATH": directory + os.pathsep + os.environ["PATH"],
                               "FAKE_DOCKER_CALLS": str(calls), "FAKE_OCCUPIED_KIND": occupied_kind,
                               "THAAP_STACK_CHECK_PROJECT": "occupied-proof"}
                result = subprocess.run(["bash", str(ROOT / "scripts/compose-stack-check.sh")],
                                        env=environment, capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn("refusing reuse or cleanup", result.stderr)
                self.assertFalse(any(line.startswith("compose ") for line in calls.read_text().splitlines()))

    def test_compose_names_are_project_scoped_and_broker_storage_is_explicit(self):
        configuration = (ROOT / "infra/docker/compose.yml").read_text()
        self.assertNotIn("container_name:", configuration)
        self.assertIn("KAFKA_LOG_DIRS: /var/lib/kafka/data", configuration)
        self.assertIn("KAFKA_HEAP_OPTS: -Xms512m -Xmx512m", configuration)
        self.assertIn("mem_limit: 1024m", configuration)
        self.assertIn("@sha256:", configuration)
        for project in ("release-proof-a", "release-proof-b"):
            result = subprocess.run(["docker", "compose", "-p", project, "-f",
                                     str(ROOT / "infra/docker/compose.yml"), "config", "--format", "json"],
                                    capture_output=True, text=True, timeout=15, check=True)
            config = json.loads(result.stdout)
            self.assertEqual(config["name"], project)
            self.assertEqual(config["volumes"]["thaap-postgres-data"]["name"], project + "_thaap-postgres-data")

    def test_all_routers_agreeing_on_an_incomplete_public_contract_still_fails(self):
        canonical = ROUTING.vite_prefixes()
        # Reopen the full omission family, not just one observed missing refund route.
        public = json.loads((ROOT / "openapi/thaap-public-api-2026-08-01.yaml").read_text())
        prefixes = {"/" + path.split("/")[1] for path in public["paths"]}
        for omitted in prefixes:
            with self.subTest(prefix=omitted), contextlib.redirect_stderr(io.StringIO()):
                bad = canonical - {omitted}
                with mock.patch.object(ROUTING, "vite_prefixes", return_value=bad), \
                     mock.patch.object(ROUTING, "nginx_prefixes", return_value=bad), \
                     mock.patch.object(ROUTING, "terraform_prefixes", return_value=bad), \
                     mock.patch.object(ROUTING, "stack_check_prefixes", return_value=bad):
                    self.assertEqual(ROUTING.main(), 1)

    def test_router_parser_failure_and_single_router_drift_fail_closed(self):
        for parser in ("vite_prefixes", "nginx_prefixes", "terraform_prefixes", "stack_check_prefixes"):
            for bad in (set(), {"/not_the_api"}):
                with self.subTest(parser=parser, bad=bad), contextlib.redirect_stderr(io.StringIO()):
                    with mock.patch.object(ROUTING, parser, return_value=bad):
                        self.assertEqual(ROUTING.main(), 1)

    def test_release_publish_choice_and_cpu_architecture_are_enforced(self):
        workflow = (ROOT / ".github/workflows/release-image.yml").read_text()
        self.assertIn("uses: docker/setup-qemu-action@v3", workflow)
        self.assertIn("platforms: arm64", workflow)
        self.assertIn("--platform linux/arm64", (ROOT / "scripts/release-identity-check.sh").read_text())
        self.assertIn('cpu_architecture        = "ARM64"', (ROOT / "infra/terraform/ecs.tf").read_text())
        decision = workflow.split("        run: |\n          if [[", 1)[1].split("      - name: Assume", 1)[0]
        decision = "if [[" + decision
        for event in ("push", "workflow_dispatch"):
            for publish in ("true", "false"):
                for configured in (True, False):
                    with self.subTest(event=event, publish=publish, configured=configured), tempfile.TemporaryDirectory() as directory:
                        output = pathlib.Path(directory) / "output"
                        summary = pathlib.Path(directory) / "summary"
                        script = decision.replace("${{ github.event_name }}", event).replace("${{ inputs.publish }}", publish)
                        environment = {**os.environ, "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary),
                                       "ECR_REGISTRY": "fixture-registry" if configured else "",
                                       "RELEASE_ROLE_ARN": "fixture-role" if configured else ""}
                        subprocess.run(["bash", "-e", "-c", script], env=environment,
                                       capture_output=True, text=True, timeout=5, check=True)
                        expected = configured and (event == "push" or publish == "true")
                        self.assertEqual(output.read_text().strip(), "publish=" + str(expected).lower())

    def test_ci_executes_mocked_topology_and_edge_family_proof(self):
        workflow = (ROOT / ".github/workflows/commit-readiness.yml").read_text()
        self.assertIn("terraform -chdir=infra/terraform test -no-color", workflow)
        self.assertIn("node --test scripts/edge-request.test.mjs", workflow)


if __name__ == "__main__":
    unittest.main()
