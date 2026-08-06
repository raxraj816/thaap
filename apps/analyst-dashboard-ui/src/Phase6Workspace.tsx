import { FormEvent, useEffect, useRef, useState } from "react";
import {
  ApiFailure,
  BulkAction,
  BulkItemPage,
  InvestigationDetail,
  InsightDrillThrough,
  InvestigationSearchPage,
  OperatorSession,
  PaymentSavedView,
  RelatedPaymentPage,
  TimelinePage,
  WorkspaceDecisionInsights,
  WorkspaceReviewInsights,
  createPaymentSavedView,
  disablePaymentSavedView,
  executePaymentSavedView,
  getBulkAssignment,
  getBulkAssignmentItems,
  getInvestigation,
  getInvestigationTimeline,
  getRelatedPayments,
  getWorkspaceDecisionInsights,
  getWorkspaceReviewInsights,
  listPaymentSavedViews,
  searchInvestigations,
  startBulkAssignment,
} from "./api";

type Props = {
  credential: string;
  operator: OperatorSession;
  path: string;
  navigate: (path: string) => void;
};

type SliceError = { message: string; status?: number; requestId?: string };

const PAYMENT_ROUTE = /^\/workspace\/payments\/([0-9a-fA-F-]{36})$/;

export function Phase6Workspace({ credential, operator, path, navigate }: Props) {
  // A drill-through is handed over in memory rather than through the URL: the route allowlist
  // carries only UUIDs and static slugs, and a filter in the address bar would be both
  // unvalidated input and a shareable link to another operator's view.
  const [drillThrough, setDrillThrough] = useState<InsightDrillThrough | null>(null);
  const paymentMatch = PAYMENT_ROUTE.exec(path);
  if (paymentMatch) {
    return <InvestigationRoute credential={credential} paymentIntentId={paymentMatch[1]}
      navigate={navigate} />;
  }
  if (path === "/workspace/insights") {
    return <InsightsRoute credential={credential} onDrillThrough={(filter) => {
      setDrillThrough(filter);
      navigate("/workspace/payments");
    }} />;
  }
  if (path === "/workspace/bulk") {
    return <BulkRoute credential={credential} operator={operator} />;
  }
  if (path === "/workspace/payment-views") {
    return <SavedViewsRoute credential={credential} operator={operator} navigate={navigate} />;
  }
  return <PaymentSearchRoute credential={credential} navigate={navigate}
    drillThrough={drillThrough} onDrillThroughApplied={() => setDrillThrough(null)} />;
}

function PaymentSearchRoute({ credential, navigate, drillThrough, onDrillThroughApplied }:
  Pick<Props, "credential" | "navigate"> & { drillThrough?: InsightDrillThrough | null;
    onDrillThroughApplied?: () => void }) {
  const [fraudAction, setFraudAction] = useState(drillThrough?.fraudActions?.[0] ?? "");
  const [page, setPage] = useState<InvestigationSearchPage | null>(null);
  const [error, setError] = useState<SliceError | null>(null);
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(0);

  async function load(cursor?: string, append = false) {
    const loadId = ++loadRef.current;
    setLoading(true);
    setError(null);
    try {
      const result = await searchInvestigations(credential, fraudAction, cursor);
      if (loadRef.current !== loadId) return;
      setPage((current) => append && current
        ? { ...result, data: [...current.data, ...result.data] } : result);
    } catch (failure) {
      if (loadRef.current === loadId) setError(toSliceError(failure));
    } finally {
      if (loadRef.current === loadId) setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    return () => { loadRef.current += 1; };
  }, [credential]);

  // The handed-over filter is consumed once, so returning to search later does not silently
  // re-apply a filter the analyst never chose again.
  useEffect(() => {
    if (drillThrough) onDrillThroughApplied?.();
  }, []);

  function submit(event: FormEvent) {
    event.preventDefault();
    void load();
  }

  return <main className="phase-workspace investigation-search" aria-labelledby="payment-search-heading">
    <section className="card phase-hero">
      <span className="section-index">PAYMENT INVESTIGATION</span>
      <h2 id="payment-search-heading">Find the payment, then establish the truth.</h2>
      <p className="muted">Discovery comes from the active projection. Opening a result switches to a source-reconciled snapshot with independently recoverable evidence.</p>
      <form className="phase-filter" onSubmit={submit}>
        <label htmlFor="fraud-action">Decision action</label>
        <select id="fraud-action" value={fraudAction}
          onChange={(event) => setFraudAction(event.target.value)}>
          <option value="">All actions</option><option>ALLOW</option><option>BLOCK</option>
          <option>REVIEW</option><option>REQUEST_3DS</option>
        </select>
        <button disabled={loading}>Apply bounded search</button>
      </form>
    </section>
    {error && <SliceFailure title="Payment search unavailable" error={error}
      retry={() => void load()} />}
    {page && <section className="card">
      <FreshnessTruth freshness={page.freshness} />
      {page.data.length === 0
        ? <p role="status">No payment investigations match this bounded view.</p>
        : <div className="table-wrap"><table>
          <thead><tr><th>Payment</th><th>Money</th><th>Decision</th><th>Risk</th>
            <th>Review / label</th><th>Coverage</th></tr></thead>
          <tbody>{page.data.map((row) => <tr key={row.paymentIntentId}>
            <td><button className="row-link" onClick={() =>
              navigate(`/workspace/payments/${row.paymentIntentId}`)}>
              {row.paymentIntentId}</button><small>{formatTime(row.lastEventRecordedAt)}</small></td>
            <td>{row.amountMinor === null || !row.currency ? "Not available"
              : formatMinor(row.amountMinor, row.currency)}</td>
            <td>{human(row.fraudAction ?? "NOT_DECIDED")}</td>
            <td>{row.riskLevel ?? "Not scored"}{row.riskScoreBasisPoints === null
              ? "" : ` · ${(row.riskScoreBasisPoints / 100).toFixed(2)}%`}</td>
            <td>{human(row.reviewStatus ?? "NO_REVIEW")} · {human(row.labelValue ?? "UNLABELLED")}</td>
            <td><TruthPill value={row.coverageStatus} /></td>
          </tr>)}</tbody>
        </table></div>}
      {page.nextCursor && <button className="secondary load-more" disabled={loading}
        onClick={() => void load(page.nextCursor!, true)}>Load next frozen page</button>}
    </section>}
    {loading && !page && <p role="status">Loading payment investigations…</p>}
  </main>;
}

function InvestigationRoute({ credential, paymentIntentId, navigate }:
  Pick<Props, "credential" | "navigate"> & { paymentIntentId: string }) {
  const [detail, setDetail] = useState<InvestigationDetail | null>(null);
  const [timeline, setTimeline] = useState<TimelinePage | null>(null);
  const [related, setRelated] = useState<RelatedPaymentPage | null>(null);
  const [errors, setErrors] = useState<Record<string, SliceError | null>>({});
  const [loading, setLoading] = useState(true);
  const loadRef = useRef(0);

  async function loadAll() {
    const loadId = ++loadRef.current;
    setLoading(true);
    setErrors({});
    const [detailResult, timelineResult, relatedResult] = await Promise.allSettled([
      getInvestigation(credential, paymentIntentId),
      getInvestigationTimeline(credential, paymentIntentId),
      getRelatedPayments(credential, paymentIntentId),
    ]);
    if (loadRef.current !== loadId) return;
    if (detailResult.status === "fulfilled") setDetail(detailResult.value);
    else setErrors((value) => ({ ...value, detail: toSliceError(detailResult.reason) }));
    if (timelineResult.status === "fulfilled") setTimeline(timelineResult.value);
    else setErrors((value) => ({ ...value, timeline: toSliceError(timelineResult.reason) }));
    if (relatedResult.status === "fulfilled") setRelated(relatedResult.value);
    else setErrors((value) => ({ ...value, related: toSliceError(relatedResult.reason) }));
    setLoading(false);
  }

  async function reloadTimeline(cursor?: string) {
    try {
      const result = await getInvestigationTimeline(credential, paymentIntentId, cursor);
      setTimeline((current) => cursor && current
        ? { ...result, data: [...current.data, ...result.data] } : result);
      setErrors((value) => ({ ...value, timeline: null }));
    } catch (failure) {
      setErrors((value) => ({ ...value, timeline: toSliceError(failure) }));
    }
  }

  async function reloadRelated(cursor?: string) {
    try {
      const result = await getRelatedPayments(credential, paymentIntentId, cursor);
      setRelated((current) => cursor && current
        ? { ...result, data: [...current.data, ...result.data] } : result);
      setErrors((value) => ({ ...value, related: null }));
    } catch (failure) {
      setErrors((value) => ({ ...value, related: toSliceError(failure) }));
    }
  }

  useEffect(() => {
    void loadAll();
    return () => { loadRef.current += 1; };
  }, [credential, paymentIntentId]);

  return <main className="phase-workspace investigation-detail" aria-labelledby="investigation-heading">
    <button className="quiet phase-back" onClick={() => navigate("/workspace/payments")}>← Payment search</button>
    <section className="card phase-hero">
      <span className="section-index">SOURCE-RECONCILED INVESTIGATION</span>
      <h2 id="investigation-heading">Payment {paymentIntentId}</h2>
      {detail && <p>Source snapshot {formatTime(detail.sourceSnapshot.sourceAsOf)} · stream through v{detail.sourceSnapshot.maximumPaymentStreamVersion} · {human(detail.sourceSnapshot.consistency)}</p>}
    </section>
    {errors.detail && <SliceFailure title="Authoritative payment detail unavailable"
      error={errors.detail} retry={() => void loadAll()} />}
    {detail && <>
      <section className="phase-summary-grid">
        <TruthCard title="Payment" status={detail.sectionStatuses.paymentLifecycle}
          value={`${human(detail.paymentLifecycle.status)} · ${formatMinor(detail.paymentLifecycle.amountMinor, detail.paymentLifecycle.currency)}`}
          detail={`${detail.paymentLifecycle.attempts.length} attempt(s) · ${human(detail.identity.trafficOrigin)}`} />
        <TruthCard title="Decision" status={detail.sectionStatuses.decisionExplanation}
          value={detail.decisionExplanation ? `${human(detail.decisionExplanation.finalAction)} · ${(detail.decisionExplanation.riskScoreBps / 100).toFixed(2)}%` : "No decision"}
          detail={detail.decisionExplanation ? `${human(detail.decisionExplanation.riskLevel)} · winner ${detail.decisionExplanation.precedence.winningControl}` : "No fraud decision is attached"} />
        <TruthCard title="Review and labels" status={detail.sectionStatuses.reviewAndLabels}
          value={`${detail.reviewAndLabels.reviews.length} reviews · ${detail.reviewAndLabels.labels.length} labels`}
          detail={detail.reviewAndLabels.labels.map((label) => human(label.value)).join(", ") || "Unlabelled"} />
        <TruthCard title="Money" status={detail.sectionStatuses.moneyAndDisputes}
          value={`${formatMinor(detail.moneyAndDisputes.capturedAmountMinor, detail.moneyAndDisputes.currency)} captured`}
          detail={`${formatMinor(detail.moneyAndDisputes.refundedAmountMinor, detail.moneyAndDisputes.currency)} refunded · ${formatMinor(detail.moneyAndDisputes.disputedAmountMinor, detail.moneyAndDisputes.currency)} disputed`} />
      </section>
      {detail.analyticsContext && <FreshnessTruth freshness={detail.analyticsContext.freshness} />}
      <section className="card">
        <div className="section-heading"><div><span className="section-index">WHY</span>
          <h2>Decision explanation</h2></div>
          <p>{detail.decisionExplanation?.modelState ?? "No decision"}</p></div>
        {!detail.decisionExplanation ? <p>No fraud decision exists for the latest attempt.</p> : <>
          <p><strong>Final action:</strong> {human(detail.decisionExplanation.finalAction)} · <strong>reasons:</strong> {detail.decisionExplanation.reasonCodes.map(human).join(", ") || "none"}</p>
          <div className="table-wrap"><table><thead><tr><th>Control</th><th>Version</th>
            <th>Availability</th><th>Outcome</th><th>Proposed action</th><th>Winner</th></tr></thead>
            <tbody>{detail.decisionExplanation.controlEvaluations.map((control) =>
              <tr key={`${control.family}-${control.code}-${control.version}`}>
                <td>{human(control.family)}<small>{control.code}</small></td>
                <td>{control.version ?? "Not versioned"}</td><td><TruthPill value={control.availability} /></td>
                <td>{human(control.outcome)}</td><td>{control.proposedAction ? human(control.proposedAction) : "None"}</td>
                <td>{control.contributedToWinningAction ? "Winning evidence" : "Retained evidence"}</td>
              </tr>)}</tbody></table></div>
        </>}
      </section>
      <section className="card action-evidence">
        <h2>Current action eligibility</h2>
        <p className="muted">These hints are not authorization. Every mutation rechecks the live actor and current source version.</p>
        <div className="pill-row">{detail.availableActions.map((action) =>
          <span className={`pill ${action.eligible ? "ok" : "danger"}`} key={action.action}>
            {human(action.action)} · {action.eligible ? "eligible" : human(action.reasonCode ?? "DENIED")}
          </span>)}</div>
      </section>
    </>}
    <section className="card">
      <div className="section-heading"><div><span className="section-index">CANONICAL ORDER</span>
        <h2>Payment timeline</h2></div><p>{timeline ? `Through stream v${timeline.maximumStreamVersion}` : "Independently loaded"}</p></div>
      {errors.timeline && <SliceFailure title="Timeline unavailable" error={errors.timeline}
        retry={() => void reloadTimeline()} />}
      {timeline?.data.length === 0 && <p>No canonical payment events are recorded.</p>}
      <ol className="timeline-list">{timeline?.data.map((entry) => <li key={entry.eventId}>
        <span className="stream-version">v{entry.streamVersion}</span><div>
          <strong>{human(entry.titleCode)}</strong><p>{human(entry.category)} · occurred {formatTime(entry.occurredAt)} · recorded {formatTime(entry.recordedAt)}</p>
        </div></li>)}</ol>
      {timeline?.nextCursor && <button className="secondary" onClick={() =>
        void reloadTimeline(timeline.nextCursor!)}>Load later stream versions</button>}
    </section>
    <section className="card">
      <div className="section-heading"><div><span className="section-index">SAFE PIVOTS</span>
        <h2>Related payments</h2></div><p>{related ? `${formatTime(related.from)} — ${formatTime(related.to)}` : "Independently loaded"}</p></div>
      {errors.related && <SliceFailure title="Related payments unavailable" error={errors.related}
        retry={() => void reloadRelated()} />}
      {related && <div className="coverage-callout"><TruthPill value={related.coverage.state} />
        <span>{related.coverage.unavailableReasons.length === 0 ? "All relation types covered"
          : `Unavailable relation types: ${related.coverage.unavailableReasons.map(human).join(", ")}`}</span></div>}
      {related?.data.length === 0 && <p>No related payments were found within this coverage boundary.</p>}
      <div className="related-grid">{related?.data.map((item) => <article key={item.paymentIntentId}>
        <button className="row-link" onClick={() => navigate(`/workspace/payments/${item.paymentIntentId}`)}>{item.paymentIntentId}</button>
        <p>{item.reasons.map(human).join(" · ")}</p><TruthPill value={item.sectionState} />
      </article>)}</div>
      {related?.nextCursor && <button className="secondary" onClick={() =>
        void reloadRelated(related.nextCursor!)}>Load more related payments</button>}
    </section>
    {loading && !detail && <p role="status">Loading source and supporting evidence…</p>}
  </main>;
}

function InsightsRoute({ credential, onDrillThrough }: Pick<Props, "credential">
  & { onDrillThrough?: (filter: InsightDrillThrough) => void }) {
  const [decision, setDecision] = useState<WorkspaceDecisionInsights | null>(null);
  const [review, setReview] = useState<WorkspaceReviewInsights | null>(null);
  const [errors, setErrors] = useState<Record<string, SliceError | null>>({});

  async function load() {
    setErrors({});
    const [decisionResult, reviewResult] = await Promise.allSettled([
      getWorkspaceDecisionInsights(credential), getWorkspaceReviewInsights(credential),
    ]);
    if (decisionResult.status === "fulfilled") setDecision(decisionResult.value);
    else setErrors((value) => ({ ...value, decision: toSliceError(decisionResult.reason) }));
    if (reviewResult.status === "fulfilled") setReview(reviewResult.value);
    else setErrors((value) => ({ ...value, review: toSliceError(reviewResult.reason) }));
  }

  useEffect(() => { void load(); }, [credential]);

  return <main className="phase-workspace" aria-labelledby="insights-heading">
    <section className="card phase-hero"><span className="section-index">DECISION INTELLIGENCE</span>
      <h2 id="insights-heading">Workload and outcomes, with their evidence boundaries intact.</h2>
      <p className="muted">Review operations are source-backed. Decision outcomes are projection-backed. Their timestamps are intentionally not merged.</p>
    </section>
    <section className="card">
      <div className="section-heading"><div><span className="section-index">AUTHORITATIVE REVIEW SOURCE</span><h2>Review operations</h2></div>
        <p>{review ? `Source as of ${formatTime(review.sourceAsOf)}` : "Loading"}</p></div>
      {errors.review && <SliceFailure title="Review insights unavailable" error={errors.review}
        retry={() => void load()} />}
      {review && <><div className="metric-grid">
        <Metric label="Waiting" value={review.backlog.total} evidence={`${review.backlog.breached} beyond SLA`} />
        <Metric label="Unassigned" value={review.backlog.unassigned} evidence="Current source state" />
        <Metric label="Assignments" value={review.assignments} evidence="Bounded event count" />
        <Metric label="Resolutions" value={review.resolutions} evidence="Bounded durable outcomes" />
      </div><p className="muted">Conflict attempts: {review.conflictedAttempts === null
        ? human(review.conflictedAttemptsStatus) : review.conflictedAttempts}</p></>}
    </section>
    <section className="card">
      <div className="section-heading"><div><span className="section-index">ACTIVE PROJECTION</span><h2>Decision and outcome evidence</h2></div>
        <p>{decision ? `${formatTime(decision.range.from)} — ${formatTime(decision.range.to)}` : "Loading"}</p></div>
      {errors.decision && <SliceFailure title="Decision insights unavailable" error={errors.decision}
        retry={() => void load()} />}
      {decision && <><FreshnessTruth freshness={decision.freshness} />
        {decision.warningCode && <p role="alert">{human(decision.warningCode)}</p>}
        <div className="metric-grid">{decision.decisionDistribution.map((segment) =>
          segment.drillThrough && onDrillThrough
            ? <button key={segment.code} className="metric-drill"
                onClick={() => onDrillThrough(segment.drillThrough!)}>
                <Metric label={human(segment.code)} value={segment.count}
                  evidence="Open these payments" /></button>
            : <Metric key={segment.code} label={human(segment.code)} value={segment.count}
                evidence="Decision distribution" />)}</div>
        <div className="rate-grid">{Object.entries(decision.rates).map(([name, rate]) =>
          <article key={name}><h3>{human(name)}</h3><strong>{rate.valueBasisPoints === null
            ? "Not computable" : `${(rate.valueBasisPoints / 100).toFixed(2)}%`}</strong>
            <p>{rate.numerator} / {rate.denominator} · {rate.unknownCount} unknown</p>
            <TruthPill value={rate.status} /></article>)}</div>
        <div className="table-wrap"><table><thead><tr><th>Currency</th><th>Captured</th>
          <th>Refunded</th><th>Disputed</th></tr></thead><tbody>
          {decision.moneyByCurrency.map((money) => <tr key={money.currency}>
            <td>{money.currency}</td><td>{formatMinor(money.capturedAmountMinor, money.currency)}</td>
            <td>{formatMinor(money.refundedAmountMinor, money.currency)}</td>
            <td>{formatMinor(money.disputedAmountMinor, money.currency)}</td></tr>)}
        </tbody></table></div><p className="muted">{human(decision.claimBoundary)}</p></>}
    </section>
  </main>;
}

function SavedViewsRoute({ credential, operator, navigate }:
  Pick<Props, "credential" | "operator" | "navigate">) {
  const [views, setViews] = useState<PaymentSavedView[] | null>(null);
  const [name, setName] = useState("");
  const [visibility, setVisibility] = useState<"PRIVATE" | "MERCHANT_SHARED">("PRIVATE");
  const [fraudAction, setFraudAction] = useState("");
  const [executed, setExecuted] = useState<{ view: PaymentSavedView;
    page: InvestigationSearchPage } | null>(null);
  const [error, setError] = useState<SliceError | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    setBusy(true); setError(null);
    try { setViews(await listPaymentSavedViews(credential)); }
    catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  useEffect(() => { void load(); }, [credential]);

  async function create(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(null);
    try {
      await createPaymentSavedView(credential, name, visibility, fraudAction);
      setName("");
      await load();
    } catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  // Disable and execute both send the version the operator actually saw. A stale version is a
  // conflict the server refuses, never a silent reapply against whatever the row says now.
  async function disable(view: PaymentSavedView) {
    setBusy(true); setError(null);
    try {
      await disablePaymentSavedView(credential, view.id, view.lifecycleVersion);
      await load();
    } catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  async function execute(view: PaymentSavedView) {
    setBusy(true); setError(null);
    try { setExecuted({ view, page: await executePaymentSavedView(credential, view.id) }); }
    catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  const canShare = operator.role === "RISK_MANAGER";
  return <main className="phase-workspace" aria-labelledby="saved-views-heading">
    <section className="card phase-hero"><span className="section-index">SAVED INVESTIGATION VIEWS</span>
      <h2 id="saved-views-heading">Keep a bounded search, not a copy of its results.</h2>
      <p className="muted">A view stores only its typed filter, sort, and digest. Executing it re-reads the active projection, so a saved view can never present stale rows as current truth.</p>
    </section>
    {operator.role === "VIEWER" ? <section className="card" role="alert">
      Viewer access is read-only. Saved views cannot be created or disabled.</section>
      : <section className="card">
      <form className="stack" onSubmit={create}>
        <label htmlFor="view-name">View name</label>
        <input id="view-name" required maxLength={80} value={name}
          onChange={(event) => setName(event.target.value)} />
        <label htmlFor="view-visibility">Visibility</label>
        <select id="view-visibility" value={visibility} onChange={(event) =>
          setVisibility(event.target.value as "PRIVATE" | "MERCHANT_SHARED")}>
          <option value="PRIVATE">PRIVATE</option>
          {canShare && <option value="MERCHANT_SHARED">MERCHANT_SHARED</option>}
        </select>
        <label htmlFor="view-action">Decision action filter</label>
        <select id="view-action" value={fraudAction}
          onChange={(event) => setFraudAction(event.target.value)}>
          <option value="">All actions</option><option>ALLOW</option><option>BLOCK</option>
          <option>REVIEW</option><option>REQUEST_3DS</option>
        </select>
        <button disabled={busy}>Save this bounded view</button>
      </form>
    </section>}
    {error && <SliceFailure title="Saved views unavailable" error={error}
      retry={() => void load()} />}
    {views && <section className="card">
      {views.length === 0 ? <p role="status">No saved investigation views yet.</p>
        : <div className="table-wrap"><table>
          <thead><tr><th>Name</th><th>Visibility</th><th>State</th><th>Digest</th>
            <th>Actions</th></tr></thead>
          <tbody>{views.map((view) => <tr key={view.id}>
            <td>{view.name}<small>v{view.lifecycleVersion}</small></td>
            <td>{human(view.visibility)}</td>
            <td><TruthPill value={view.lifecycleStatus} /></td>
            <td><code>{view.definitionDigest.slice(0, 12)}</code></td>
            <td>
              <button className="secondary" disabled={busy || view.lifecycleStatus !== "ACTIVE"}
                onClick={() => void execute(view)}>Execute</button>
              {operator.role !== "VIEWER" && <button className="secondary"
                disabled={busy || view.lifecycleStatus !== "ACTIVE"}
                onClick={() => void disable(view)}>Disable</button>}
            </td>
          </tr>)}</tbody>
        </table></div>}
    </section>}
    {executed && <section className="card" aria-live="polite">
      <div className="section-heading"><div><span className="section-index">VIEW RESULT</span>
        <h2>{executed.view.name}</h2></div></div>
      <FreshnessTruth freshness={executed.page.freshness} />
      {executed.page.data.length === 0
        ? <p role="status">This saved view matches no payment right now.</p>
        : <div className="table-wrap"><table>
          <thead><tr><th>Payment</th><th>Decision</th><th>Coverage</th></tr></thead>
          <tbody>{executed.page.data.map((row) => <tr key={row.paymentIntentId}>
            <td><button className="row-link" onClick={() =>
              navigate(`/workspace/payments/${row.paymentIntentId}`)}>
              {row.paymentIntentId}</button></td>
            <td>{human(row.fraudAction ?? "NOT_DECIDED")}</td>
            <td><TruthPill value={row.coverageStatus} /></td>
          </tr>)}</tbody>
        </table></div>}
    </section>}
    {busy && !views && <p role="status">Loading saved investigation views…</p>}
  </main>;
}

function BulkRoute({ credential, operator }: Pick<Props, "credential" | "operator">) {
  const [action, setAction] = useState("ASSIGN_TO_SELF");
  const [target, setTarget] = useState("");
  const [itemsText, setItemsText] = useState("");
  const [run, setRun] = useState<BulkAction | null>(null);
  const [items, setItems] = useState<BulkItemPage | null>(null);
  const [error, setError] = useState<SliceError | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(null);
    try {
      const parsed = parseBulkItems(itemsText);
      const created = await startBulkAssignment(credential, action,
        action === "ASSIGN_TO_OPERATOR" ? target : null, parsed);
      setRun(created);
      setItems(await getBulkAssignmentItems(credential, created.id));
    } catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  async function refresh() {
    if (!run) return;
    setBusy(true); setError(null);
    try {
      setRun(await getBulkAssignment(credential, run.id));
      setItems(await getBulkAssignmentItems(credential, run.id));
    } catch (failure) { setError(toSliceError(failure)); }
    finally { setBusy(false); }
  }

  return <main className="phase-workspace" aria-labelledby="bulk-heading">
    <section className="card phase-hero"><span className="section-index">BOUNDED REVIEW OPERATIONS</span>
      <h2 id="bulk-heading">Assign a review workload with item-level truth.</h2>
      <p className="muted">At most 25 distinct cases. Each carries the source assignment version you observed; stale work fails independently.</p>
    </section>
    {operator.role === "VIEWER" ? <section className="card" role="alert">
      Viewer access is read-only. Bulk assignment is unavailable.</section> : <section className="card">
      <form className="stack" onSubmit={submit}>
        <label htmlFor="bulk-action">Assignment operation</label>
        <select id="bulk-action" value={action} onChange={(event) => setAction(event.target.value)}>
          <option>ASSIGN_TO_SELF</option><option>RELEASE</option>
          {operator.role === "RISK_MANAGER" && <option>ASSIGN_TO_OPERATOR</option>}
        </select>
        {action === "ASSIGN_TO_OPERATOR" && <><label htmlFor="bulk-target">Target operator UUID</label>
          <input id="bulk-target" required pattern="[0-9a-fA-F-]{36}" value={target}
            onChange={(event) => setTarget(event.target.value)} /></>}
        <label htmlFor="bulk-items">Review case UUID and expected version, one per line</label>
        <textarea id="bulk-items" required rows={6} value={itemsText}
          placeholder="00000000-0000-0000-0000-000000000000:0"
          onChange={(event) => setItemsText(event.target.value)} />
        <button disabled={busy}>Start durable bulk action</button>
      </form>
    </section>}
    {error && <SliceFailure title="Bulk action unavailable" error={error}
      retry={run ? () => void refresh() : undefined} />}
    {run && <section className="card" aria-live="polite">
      <div className="section-heading"><div><span className="section-index">DURABLE PROGRESS</span>
        <h2>{human(run.lifecycleStatus)}</h2></div><button className="secondary" disabled={busy}
          onClick={() => void refresh()}>Refresh source progress</button></div>
      <p>{run.succeededCount} succeeded · {run.failedCount} failed · {run.itemCount} total</p>
      <code>{run.requestDigest}</code>
      <div className="table-wrap"><table><thead><tr><th>Review</th><th>Expected</th>
        <th>Result</th><th>Observed / final</th></tr></thead><tbody>
        {items?.data.map((item) => <tr key={item.id}><td>{item.reviewCaseId}</td>
          <td>v{item.expectedAssignmentVersion}</td><td><TruthPill value={item.lifecycleStatus} />
            {item.failureCode && <small>{human(item.failureCode)}</small>}</td>
          <td>{item.observedAssignmentVersion ?? "—"} / {item.finalAssignmentVersion ?? "—"}</td></tr>)}
      </tbody></table></div>
    </section>}
  </main>;
}

function TruthCard({ title, status, value, detail }: { title: string;
  status?: { state: string; authority: string; reasonCode: string | null };
  value: string; detail: string }) {
  return <article className="card truth-card"><div><h3>{title}</h3>
    <TruthPill value={status?.state ?? "UNAVAILABLE"} /></div><strong>{value}</strong>
    <p>{detail}</p><small>{human(status?.authority ?? "AUTHORITY_UNAVAILABLE")}</small></article>;
}

function Metric({ label, value, evidence }: { label: string; value: number; evidence: string }) {
  return <article><h3>{label}</h3><strong>{value.toLocaleString()}</strong><p>{evidence}</p></article>;
}

function FreshnessTruth({ freshness }: { freshness: InvestigationSearchPage["freshness"] }) {
  const numeric = freshness.state === "FRESH" || freshness.state === "LAGGING";
  return <div className={`freshness-banner freshness-${freshness.state.toLowerCase()}`}
    role={numeric ? "status" : "alert"}><div><strong>Projection evidence</strong>
      <TruthPill value={freshness.state} /></div>
    <p>{numeric ? `${freshness.appliedEventCount} of ${freshness.eligibleEventCount} eligible events applied.`
      : "Numeric completeness is not established; source detail remains independently available."}</p>
    <p className="muted">Generation {freshness.generationNumber} · {human(freshness.trafficOriginPolicy)}
      {freshness.sourceCutoff ? ` · cutoff ${formatTime(freshness.sourceCutoff)}` : " · no source cutoff"}</p></div>;
}

function SliceFailure({ title, error, retry }: { title: string; error: SliceError;
  retry?: () => void }) {
  return <div className="slice-failure" role="alert"><strong>{title}</strong>
    <p>{error.message}{error.requestId ? ` (request ${error.requestId})` : ""}</p>
    {retry && <button className="quiet" onClick={retry}>Retry this slice</button>}</div>;
}

function TruthPill({ value }: { value: string }) {
  const positive = ["AVAILABLE", "COMPLETE", "FRESH", "COMPUTABLE", "SUCCEEDED"].includes(value);
  return <span className={`pill ${positive ? "ok" : value === "EMPTY" ? "" : "danger"}`}>
    {human(value)}</span>;
}

function parseBulkItems(value: string): { reviewCaseId: string; expectedAssignmentVersion: number }[] {
  const lines = value.split("\n").map((line) => line.trim()).filter(Boolean);
  if (lines.length < 1 || lines.length > 25) throw new Error("Enter between 1 and 25 review cases");
  const seen = new Set<string>();
  return lines.map((line) => {
    const match = /^([0-9a-fA-F-]{36}):(0|[1-9][0-9]*)$/.exec(line);
    if (!match || seen.has(match[1].toLowerCase())) {
      throw new Error("Every item must be a distinct UUID followed by a non-negative version");
    }
    seen.add(match[1].toLowerCase());
    return { reviewCaseId: match[1], expectedAssignmentVersion: Number(match[2]) };
  });
}

function toSliceError(failure: unknown): SliceError {
  const value = failure as ApiFailure;
  return { message: value.message || "The request failed", status: value.status,
    requestId: value.requestId };
}

function human(value: string): string {
  return value.replaceAll("_", " ").replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase()
    .replace(/^./, (character) => character.toUpperCase());
}

function formatTime(value: string): string {
  return new Date(value).toISOString();
}

function formatMinor(value: number, currency: string): string {
  try { return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(value / 100); }
  catch { return `${value} ${currency} minor units`; }
}
