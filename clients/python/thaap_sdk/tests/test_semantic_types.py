"""C58-DAT-001 — meanings, not just shapes, survive the wire.

The compatibility gate proves a field did not disappear. These tests prove the
field still means what an integrator assumed. Every failure mode here is silent:
nothing raises, a number is simply wrong by a factor of a hundred, or off by a
cent, or shifted by a timezone, and the integrator finds out from a reconciliation
report weeks later.
"""

from __future__ import annotations

import datetime as dt
import json
import uuid

import pytest

from thaap_sdk import Credentials, ThaapClient
from thaap_sdk.generated.models.analytics_freshness import AnalyticsFreshness
from thaap_sdk.generated.models.currency_money import CurrencyMoney
from thaap_sdk.generated.models.decision_explanation import DecisionExplanation
from thaap_sdk.generated.models.payment_intent_response import PaymentIntentResponse

from tests.sandbox_fixture import MERCHANT_KEY, RISK_KEY, SandboxServer


def _risk(server: SandboxServer) -> ThaapClient:
    return ThaapClient(Credentials(risk_operator_key=RISK_KEY), base_url=server.base_url)


def test_minor_units_survive_beyond_the_float_precision_boundary() -> None:
    """2**53 + 1 is the first integer a float64 cannot represent.

    A generated model that typed money as a float would round this to an even
    number and lose a cent, and no test that only uses small amounts would notice.
    """
    beyond_float = 9_007_199_254_740_993
    assert float(beyond_float) != beyond_float

    money = CurrencyMoney.from_json(
        json.dumps({"currency": "USD", "capturedAmountMinor": beyond_float})
    )
    assert money.captured_amount_minor == beyond_float
    assert isinstance(money.captured_amount_minor, int)
    assert not isinstance(money.captured_amount_minor, float)
    assert json.loads(money.to_json())["capturedAmountMinor"] == beyond_float


@pytest.mark.parametrize("basis_points", [0, 1, 5_000, 9_999, 10_000])
def test_risk_scores_stay_in_basis_points_and_never_become_percent(basis_points: int) -> None:
    explanation = DecisionExplanation.from_json(
        json.dumps({"riskScoreBps": basis_points, "modelState": "NOT_CONFIGURED"})
    )
    assert explanation.risk_score_bps == basis_points
    assert isinstance(explanation.risk_score_bps, int)
    # The rendering rule lives with the caller, and it is division by 100, not by 1.
    assert explanation.risk_score_bps / 100 <= 100


def test_a_fractional_score_is_rejected_rather_than_silently_truncated() -> None:
    with pytest.raises(ValueError):
        DecisionExplanation.from_json(json.dumps({"riskScoreBps": 42.5}))


def test_absent_model_state_is_never_reported_as_a_zero_score() -> None:
    """Phase 7 has not shipped a model. A zero here would read as 'scored, low risk'."""
    explanation = DecisionExplanation.from_json(json.dumps({"modelState": "NOT_CONFIGURED"}))
    assert explanation.model_state == "NOT_CONFIGURED"
    assert explanation.risk_score_bps is None


def test_instants_stay_timezone_aware_utc_with_sub_second_precision() -> None:
    freshness = AnalyticsFreshness.from_json(
        json.dumps({"state": "FRESH", "sourceCutoff": "2026-08-01T00:00:00.123456Z"})
    )
    cutoff = freshness.source_cutoff
    assert isinstance(cutoff, dt.datetime)
    # A naive datetime is the bug: it silently becomes local time in every comparison.
    assert cutoff.tzinfo is not None
    assert cutoff.utcoffset() == dt.timedelta(0)
    assert cutoff.microsecond == 123_456


def test_identifiers_are_parsed_as_uuids_rather_than_interchangeable_strings() -> None:
    identifier = uuid.uuid4()
    intent = PaymentIntentResponse.from_json(
        json.dumps({"id": str(identifier), "amount": 100, "currency": "USD"})
    )
    assert intent.id == identifier
    assert isinstance(intent.id, uuid.UUID)
    with pytest.raises(ValueError):
        PaymentIntentResponse.from_json(json.dumps({"id": "not-a-uuid"}))


def test_a_closed_enum_rejects_an_unknown_value_instead_of_inventing_one() -> None:
    """Traffic origin decides whether a payment enters live fraud metrics.

    Coercing an unrecognised origin to a default would be worse than failing:
    synthetic volume would quietly land in the live partition.
    """
    with pytest.raises(ValueError):
        PaymentIntentResponse.from_json(
            json.dumps({"amount": 1, "currency": "USD", "trafficOrigin": "PARTNER_NETWORK"})
        )


def test_partial_projection_state_reaches_the_caller_unflattened() -> None:
    """PARTIAL must not arrive looking like FRESH with smaller numbers."""
    with SandboxServer() as server:
        with _risk(server) as client:
            summary = client.analytics().get_risk_analytics_merchant_summary()
    freshness = summary.freshness
    assert freshness.state == "PARTIAL"
    # The reproducibility set: without these a published number cannot be re-derived.
    assert freshness.generation_number == 4
    assert freshness.eligible_event_count == 120
    assert freshness.applied_event_count == 118
    assert freshness.unavailable_stream_count == 2
    assert freshness.traffic_origin_policy == "LIVE_ONLY"
    # The gap is visible rather than rounded away.
    assert freshness.eligible_event_count > freshness.applied_event_count


def test_source_and_projection_as_of_boundaries_stay_separate() -> None:
    """Source truth and projection freshness answer different questions.

    Collapsing them into one timestamp is how an analyst comes to believe a stale
    aggregate was read at the same instant as the authoritative payment row.
    """
    with SandboxServer() as server:
        with _risk(server) as client:
            detail = client.investigations().get_risk_workspace_payments_by_payment_intent_id(
                uuid.uuid4()
            )
    assert detail.source_snapshot.source_as_of.tzinfo is not None
    assert detail.source_snapshot.maximum_payment_stream_version == 7
    assert detail.analytics_context.freshness.state == "FRESH"
    assert detail.source_snapshot.source_as_of != detail.analytics_context.freshness.source_cutoff
    assert detail.decision_explanation.model_state == "NOT_CONFIGURED"
    assert detail.decision_explanation.risk_score_bps == 10_000
    # A section that is only partially covered says so rather than reading as empty.
    assert detail.section_statuses["relatedLinksSummary"].state == "PARTIAL"


def test_large_money_survives_a_full_round_trip_through_the_client() -> None:
    with SandboxServer() as server:
        with _risk(server) as client:
            detail = client.investigations().get_risk_workspace_payments_by_payment_intent_id(
                uuid.uuid4()
            )
    money = detail.money_and_disputes
    assert money.captured_amount_minor == 9_007_199_254_740_993
    assert money.currency == "USD"
    # Money stays separated by currency; nothing sums USD with another currency.
    assert money.refunded_amount_minor == 0


def test_one_pager_traverses_every_cursor_paged_investigation_route() -> None:
    """TD-0006's client half: one loop, no per-route branch.

    A pager that branched on the collection name would have a path exercised only by
    the least-used route, and that is the path that silently drops a page.
    """
    from thaap_sdk import PaginationError, iterate_rows
    from thaap_sdk.generated.models.timeline_page import TimelinePage
    from thaap_sdk.generated.models.webhook_activity_page import WebhookActivityPage

    timeline_pages = [
        TimelinePage.from_json(json.dumps({
            "maximumStreamVersion": 3, "nextCursor": "cursor-2",
            "data": [{"streamVersion": 1, "eventType": "payment.created"}]})),
        TimelinePage.from_json(json.dumps({
            "maximumStreamVersion": 3, "nextCursor": None,
            "data": [{"streamVersion": 2, "eventType": "payment.authorized"}]})),
    ]
    rows = list(iterate_rows(lambda cursor: timeline_pages[0 if cursor is None else 1]))
    assert [row.stream_version for row in rows] == [1, 2]

    # The identical pager, unmodified, over a different route's page type.
    webhook_page = WebhookActivityPage.from_json(json.dumps({
        "nextCursor": None, "data": [{"eventType": "payment.captured", "status": "DELIVERED"}]}))
    assert len(list(iterate_rows(lambda _cursor: webhook_page))) == 1

    # A server that never stops handing back a cursor must fail loudly rather than
    # look like a hang on a long payment history.
    repeating = TimelinePage.from_json(json.dumps({
        "maximumStreamVersion": 1, "nextCursor": "stuck", "data": []}))
    with pytest.raises(PaginationError):
        list(iterate_rows(lambda _cursor: repeating))
