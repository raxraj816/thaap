#!/usr/bin/env bash
set -euo pipefail

date_arg=""
deep_dive_required=false
run_tests=false
invariant_matrix=""
closure_dossier=""

for arg in "$@"; do
  case "$arg" in
    --deep-dive-required)
      deep_dive_required=true
      ;;
    --run-tests)
      run_tests=true
      ;;
    --invariant-matrix=*)
      invariant_matrix="${arg#*=}"
      ;;
    --closure-dossier=*)
      closure_dossier="${arg#*=}"
      ;;
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      date_arg="$arg"
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: scripts/commit-readiness-check.sh [YYYY-MM-DD] [--deep-dive-required] [--run-tests] [--invariant-matrix=PATH] [--closure-dossier=PATH]" >&2
      exit 2
      ;;
  esac
done

if [[ "$deep_dive_required" == true ]]; then
  run_tests=true
fi

if [[ -z "$date_arg" ]]; then
  date_arg="$(date +%F)"
fi

missing=0

echo "Commit readiness check for ${date_arg}"
echo

required_files=(
  "README.md"
  "apps/payments-api/README.md"
  "docs/IMPLEMENTATION_MASTER_PLAN.md"
  "docs/VISION.md"
  "docs/THAAP_VISION_COVERAGE_MATRIX.md"
  "docs/COMMIT_READINESS_CHECKLIST.md"
  "docs/ESCAPED_REVIEW_REGISTER.md"
  "docs/INVARIANT_PROOF_REVIEW_PROCESS.md"
  "docs/TECHNICAL_DEBT_REGISTER.md"
)

for file in "${required_files[@]}"; do
  if [[ -f "$file" ]]; then
    echo "OK required doc exists: $file"
  else
    echo "MISSING required doc: $file"
    missing=1
  fi
done

echo
echo "Checking for obviously stale root README status..."
if rg -n "Chapter 2 is complete|Chapter 3 is complete" README.md >/dev/null; then
  echo "STALE README.md status found:"
  rg -n "Chapter 2 is complete|Chapter 3 is complete" README.md
  missing=1
else
  echo "OK README.md does not advertise stale Chapter 2/Chapter 3 status."
fi

echo
echo "Checking canonical technical and implementation debt..."
scripts/technical-debt-check.py

echo
echo "Checking permanent product-vision coverage and roadmap ownership..."
scripts/vision-coverage-check.py

echo
echo "Checking escaped-review quality records..."
scripts/escaped-review-check.py

echo
echo "Checking durable executable artifact naming..."
scripts/executable-artifact-naming-check.py

echo
echo "Checking chapter invariant-to-proof traceability..."
if [[ -n "$invariant_matrix" ]]; then
  scripts/invariant-proof-check.py "$invariant_matrix"
elif [[ "$deep_dive_required" == true ]]; then
  echo "MISSING --invariant-matrix=PATH for non-trivial implementation work"
  missing=1
  scripts/invariant-proof-check.py
else
  scripts/invariant-proof-check.py
fi

echo
echo "Checking integrated chapter closure evidence..."
if [[ -n "$invariant_matrix" && -n "$closure_dossier" ]]; then
  scripts/chapter-closure-check.py "$invariant_matrix" "$closure_dossier"
elif [[ "$deep_dive_required" == true ]]; then
  if [[ -z "$invariant_matrix" ]]; then
    echo "MISSING --invariant-matrix=PATH for chapter closure"
  fi
  if [[ -z "$closure_dossier" ]]; then
    echo "MISSING --closure-dossier=PATH for non-trivial implementation work"
  fi
  missing=1
else
  echo "SKIP closure dossier for trivial work; provide both chapter artifacts to validate closure."
fi

if [[ "$invariant_matrix" == *"CHAPTER_43_INVARIANT_PROOF_MATRIX.md" \
   || "$invariant_matrix" == *"CHAPTER_43R_INVARIANT_PROOF_MATRIX.md" ]]; then
  echo
  echo "Checking every inherited Phase 4.5 chapter closure..."
  scripts/phase-closure-check.py
fi

if [[ "$invariant_matrix" == *"CHAPTER_21_INVARIANT_PROOF_MATRIX.md" ]]; then
  echo
  echo "Checking five independent Chapter 21 state-machine reviews..."
  scripts/slice-review-check.py --require-verified
fi

echo
if [[ "$deep_dive_required" == true ]]; then
  scripts/learning-day-check.sh "$date_arg" --deep-dive-required
else
  scripts/learning-day-check.sh "$date_arg"
fi

echo
echo "Git status summary:"
git status --short

echo
echo "Diff stat:"
git diff --stat

echo
echo "Senior Staff adversarial review gate:"
echo "- Payment/fraud invariant review: lifecycle, fraud lineage, idempotency, state transitions."
echo "- Tenant isolation and security review: merchant scoping, actor identity, credentials, authorization boundaries."
echo "- Sensitive-data/redaction review: PAN, CVV, PII, API keys, tokens, fingerprints, bank/tax data, idempotency secrets."
echo "- Transaction boundary and rollback review: partial writes, retries, replay, external side effects, false truth."
echo "- Observability and failure-mode review: logs, metrics, comments, debuggability, consciously deferred signals."
echo "- Test evidence review: riskiest invariant, rollback path, tenant boundary, privacy boundary, idempotency behavior."
echo "- Forced-interleaving review: dangerous ordering, actual lock/state boundary, production-database postcondition."
echo "- Protocol-boundary review: authenticated identity, exact bytes, hostname/address identity, persistence precision, reference clients."
echo "- Boundary-family review: every escaped grammar, numeric, concurrency, dependency, migration, or authorization finding has partition proof plus a real-boundary regression."
echo "- Custom-infrastructure review: accepted ADR, narrow ownership surface, complete boundary table, and replacement trigger."
echo "- Severity closure: P0/P1 and frozen-promise P2 findings are fixed and proven; future-scale P2 has TD-NNNN."
echo "- Escaped-review quality: every escaped finding has ER-NNNN, root cause, resolution evidence, and process correction."
echo "- Closure dossier: promises, failures, enforcement, proof, migration, demo, operations, debt, and source status reconcile."
echo "- Stripe/Mastercard reviewer pass: first likely objection is fixed, tested, documented, or explicitly deferred."
echo "- Cheap-fix pull-forward: valid low-cost review comments are fixed before handoff."
echo "- Deferral discipline: every true deferral has a TD-NNNN ID, reason, owner phase/slice, DRI, trigger, done criteria, and closure evidence."
echo "- Vision coverage: every VP-NNN promise has current evidence, an exact remaining gap, owner phase/slice, DRI, trigger, product demo, and done evidence."
echo
echo "Review disposition format:"
echo "- Fixed now: implemented and covered by tests/docs where relevant."
echo "- Accepted risk: acceptable for this simulator/product slice, with reason recorded."
echo "- Deferred: registered under TD-NNNN and blocked by a named missing invariant/boundary/lineage, with exact owner and trigger."
echo "- Rejected as not applicable: conflicts with current product truth, simulator scope, or architecture direction."

if [[ "$run_tests" == true ]]; then
  echo
  echo "Running backend tests..."
  mvn -f apps/payments-api/pom.xml -Dmaven.repo.local=.m2/repository test
  echo
  echo "Running repository Python tests..."
  python3 -m unittest discover -s scripts -p '*_tests.py'
fi

echo
echo "Manual review reminders:"
echo "- Check root README and service README for truthful current status."
echo "- Check master plan if scope, sequencing, or invariants changed."
echo "- Check runtime smoke test evidence if local startup behavior changed."
echo "- Check that unrelated files are not mixed into the commit."
echo "- Include adversarial-review status, cheap fixes, true deferrals, and proof in the final handoff."
echo "- Include affected VP-NNN vision promises and confirm that phase work advances product truth rather than duplicating proven surfaces."
echo "- Include invariant-matrix, traceability-pass, hostile failure-sequence-pass, and boundary-family-pass status."
echo "- Include closure-dossier, matrix-provenance, escaped-review, and custom-infrastructure status."
echo "- Name every new, changed, or resolved TD-NNNN entry in the final handoff."

exit "$missing"
