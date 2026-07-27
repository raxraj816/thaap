import { FormEvent, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  ApiFailure,
  QueuePage,
  QueueRow,
  ReviewNote,
  SavedSearch,
  SimulationReport,
  SimulationRun,
  GovernedRule,
  SafetyException,
  DisputeWorkflow,
  DeveloperCatalogue,
  DeveloperScenarioReport,
  DeveloperScenarioRun,
  WebhookDeadLetter,
  WebhookVerification,
  MonitoringSummary,
  MonitoringCohort,
  OperatorSession,
  createNote,
  createSimulation,
  executeSavedSearch,
  getSimulation,
  getSimulationReport,
  listNotes,
  listSavedSearches,
  saveSearch,
  searchQueue,
  redactNote,
  listGovernedRules,
  listSafetyExceptions,
  transitionControl,
  listDisputes,
  reviseDisputeEvidence,
  submitDispute,
  loadDeveloperCatalogue,
  createDeveloperScenario,
  claimDeveloperScenario,
  executeDeveloperScenarioStep,
  completeDeveloperScenarioStep,
  getDeveloperScenarioReport,
  listWebhookDeadLetters,
  redeliverWebhook,
  getOperatorSession,
  getMonitoringSummary,
  getMonitoringCohort,
  verifyWebhookEndpoint,
} from "./api";

type FailureState = "authentication" | "denied" | "stale" | "rate-limited" | "dependency" | "invalid";
type Notice = { kind: "error" | "success"; message: string; state?: FailureState } | null;
type WorkspaceSlice = "monitoring" | "reviews" | "savedSearches" | "rules" | "exceptions" | "disputes";
type SliceFailure = { state: FailureState; message: string };

export function App() {
  const queryClient = useQueryClient();
  const [credential, setCredential] = useState("");
  const [status, setStatus] = useState("OPEN");
  const [slaState, setSlaState] = useState("");
  const [page, setPage] = useState<QueuePage | null>(null);
  const [savedSearches, setSavedSearches] = useState<SavedSearch[]>([]);
  const [selected, setSelected] = useState<QueueRow | null>(null);
  const [notes, setNotes] = useState<ReviewNote[]>([]);
  const [noteDraft, setNoteDraft] = useState("");
  const [supersedesNoteId, setSupersedesNoteId] = useState<string | null>(null);
  const [searchName, setSearchName] = useState("");
  const [notice, setNotice] = useState<Notice>(null);
  const [candidateVersionId, setCandidateVersionId] = useState("");
  const [cohortStart, setCohortStart] = useState("");
  const [cohortEnd, setCohortEnd] = useState("");
  const [includeSynthetic, setIncludeSynthetic] = useState(false);
  const [simulation, setSimulation] = useState<SimulationRun | null>(null);
  const [simulationReport, setSimulationReport] = useState<SimulationReport | null>(null);
  const [governedRules, setGovernedRules] = useState<GovernedRule[]>([]);
  const [safetyExceptions, setSafetyExceptions] = useState<SafetyException[]>([]);
  const [activationRuns, setActivationRuns] = useState<Record<string, string>>({});
  const [disputes, setDisputes] = useState<DisputeWorkflow[]>([]);
  const [deadLetters, setDeadLetters] = useState<WebhookDeadLetter[] | null>(null);
  const [operatorSession, setOperatorSession] = useState<OperatorSession | null>(null);
  const [monitoring, setMonitoring] = useState<MonitoringSummary | null>(null);
  const [monitoringCohort, setMonitoringCohort] = useState<MonitoringCohort | null>(null);
  const [monitoringIncludesSynthetic, setMonitoringIncludesSynthetic] = useState(false);
  const [developerApiKey, setDeveloperApiKey] = useState("");
  const [scenarioRunnerToken, setScenarioRunnerToken] = useState("");
  const [scenarioSeed, setScenarioSeed] = useState("merchant-quickstart");
  const [scenarioType, setScenarioType] = useState<DeveloperScenarioRun["scenarioType"]>("APPROVED_PAYMENT");
  const [developerCatalogue, setDeveloperCatalogue] = useState<DeveloperCatalogue | null>(null);
  const [developerRun, setDeveloperRun] = useState<DeveloperScenarioRun | null>(null);
  const [developerReport, setDeveloperReport] = useState<DeveloperScenarioReport | null>(null);
  const [webhookEndpointId, setWebhookEndpointId] = useState("");
  const [webhookVerification, setWebhookVerification] = useState<WebhookVerification | null>(null);
  const [developerWorkerId] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [retryTask, setRetryTask] = useState<(() => Promise<void>) | null>(null);
  const [sliceFailures, setSliceFailures] = useState<Partial<Record<WorkspaceSlice, SliceFailure>>>({});
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    setNoteDraft("");
    setSupersedesNoteId(null);
    if (!selected || !credential) {
      setNotes([]);
      return;
    }
    void loadNotes(selected);
  }, [selected]);

  function failureState(error: unknown): FailureState {
    const statusCode = (error as ApiFailure).status;
    if (statusCode === 401) return "authentication";
    if (statusCode === 403 || statusCode === 404) return "denied";
    if (statusCode === 409 || statusCode === 412) return "stale";
    if (statusCode === 429) return "rate-limited";
    if (statusCode !== undefined && statusCode >= 500) return "dependency";
    return "invalid";
  }

  function explain(error: unknown): string {
    const failure = error as ApiFailure;
    return failure.requestId
      ? `${failure.message} (request ${failure.requestId})`
      : failure.message ?? "The workspace request failed";
  }

  async function run(task: () => Promise<void>, retryable = true) {
    setBusy(true);
    setNotice(null);
    try {
      await task();
      setRetryTask(null);
    } catch (error) {
      const state = failureState(error);
      setNotice({ kind: "error", message: explain(error), state });
      setRetryTask(retryable ? () => task : null);
    } finally {
      setBusy(false);
    }
  }

  async function connect(event: FormEvent) {
    event.preventDefault();
    await run(async () => {
      queryClient.clear();
      const session = await queryClient.fetchQuery({ queryKey: ["operator-session"],
        queryFn: () => getOperatorSession(credential) });
      const [summaryResult, queueResult, searchesResult, rulesResult, exceptionsResult,
        disputesResult] = await Promise.allSettled([
        queryClient.fetchQuery({ queryKey: ["monitoring", "MERCHANT_API_ONLY"],
          queryFn: () => getMonitoringSummary(credential, false) }),
        searchQueue(credential, status, slaState),
        listSavedSearches(credential),
        listGovernedRules(credential),
        listSafetyExceptions(credential),
        listDisputes(credential),
      ]);

      const results = [summaryResult, queueResult, searchesResult, rulesResult,
        exceptionsResult, disputesResult];
      const authenticationFailure = results.find((result) => result.status === "rejected"
        && failureState(result.reason) === "authentication");
      if (authenticationFailure?.status === "rejected") {
        throw authenticationFailure.reason;
      }

      const failures: Partial<Record<WorkspaceSlice, SliceFailure>> = {};
      function consume<T>(slice: WorkspaceSlice, result: PromiseSettledResult<T>,
        apply: (value: T) => void, clear: () => void) {
        if (result.status === "fulfilled") {
          apply(result.value);
          return;
        }
        clear();
        failures[slice] = { state: failureState(result.reason), message: explain(result.reason) };
      }

      consume("monitoring", summaryResult, setMonitoring, () => setMonitoring(null));
      consume("reviews", queueResult, setPage, () => setPage(null));
      consume("savedSearches", searchesResult, setSavedSearches, () => setSavedSearches([]));
      consume("rules", rulesResult, setGovernedRules, () => setGovernedRules([]));
      consume("exceptions", exceptionsResult, setSafetyExceptions, () => setSafetyExceptions([]));
      consume("disputes", disputesResult, setDisputes, () => setDisputes([]));
      setOperatorSession(session);
      setSliceFailures(failures);
      const unavailableCount = Object.keys(failures).length;
      setNotice({ kind: "success", message: unavailableCount === 0
        ? "Workspace connected. Credential remains in memory only."
        : `Workspace connected with ${unavailableCount} unavailable ${unavailableCount === 1 ? "slice" : "slices"}. Available sections remain usable; credential remains in memory only.` });
      headingRef.current?.focus();
    });
  }

  function clearSliceFailure(slice: WorkspaceSlice) {
    setSliceFailures((current) => {
      const next = { ...current };
      delete next[slice];
      return next;
    });
  }

  async function retryWorkspaceSlice(slice: WorkspaceSlice) {
    await run(async () => {
      switch (slice) {
        case "monitoring": {
          const policy = monitoringIncludesSynthetic ? "INCLUDE_SYNTHETIC" : "MERCHANT_API_ONLY";
          await queryClient.invalidateQueries({ queryKey: ["monitoring", policy] });
          setMonitoring(await queryClient.fetchQuery({ queryKey: ["monitoring", policy],
            queryFn: () => getMonitoringSummary(credential, monitoringIncludesSynthetic) }));
          break;
        }
        case "reviews":
          setPage(await searchQueue(credential, status, slaState));
          break;
        case "savedSearches":
          setSavedSearches(await listSavedSearches(credential));
          break;
        case "rules":
          setGovernedRules(await listGovernedRules(credential));
          break;
        case "exceptions":
          setSafetyExceptions(await listSafetyExceptions(credential));
          break;
        case "disputes":
          setDisputes(await listDisputes(credential));
          break;
      }
      clearSliceFailure(slice);
      setNotice({ kind: "success", message: `${workspaceSliceLabel(slice)} restored.` });
    });
  }

  async function refreshMonitoring(includeSynthetic = monitoringIncludesSynthetic) {
    await run(async () => {
      const policy = includeSynthetic ? "INCLUDE_SYNTHETIC" : "MERCHANT_API_ONLY";
      await queryClient.invalidateQueries({ queryKey: ["monitoring", policy] });
      const summary = await queryClient.fetchQuery({ queryKey: ["monitoring", policy],
        queryFn: () => getMonitoringSummary(credential, includeSynthetic) });
      setMonitoring(summary);
      setMonitoringCohort(null);
      clearSliceFailure("monitoring");
    });
  }

  async function drillIntoMetric(metricCode: string) {
    if (!monitoring) return;
    await run(async () => setMonitoringCohort(await getMonitoringCohort(
      credential, metricCode, monitoring)));
  }

  async function refreshQueue(cursor?: string) {
    await run(async () => {
      setPage(await searchQueue(credential, status, slaState, cursor));
      clearSliceFailure("reviews");
    });
  }

  async function loadNotes(review: QueueRow) {
    await run(async () => setNotes(await listNotes(credential, review.id, review.assignmentVersion)));
  }

  async function submitNote(event: FormEvent) {
    event.preventDefault();
    if (!selected) return;
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      if (supersedesNoteId) {
        await createNote(credential, selected.id, selected.assignmentVersion, noteDraft,
          supersedesNoteId, idempotencyKey);
      } else {
        await createNote(credential, selected.id, selected.assignmentVersion, noteDraft,
          undefined, idempotencyKey);
      }
      setNoteDraft("");
      setSupersedesNoteId(null);
      setNotes(await listNotes(credential, selected.id, selected.assignmentVersion));
      setNotice({ kind: "success", message: supersedesNoteId
        ? "Protected correction recorded." : "Protected note recorded." });
    });
  }

  async function submitSavedSearch(event: FormEvent) {
    event.preventDefault();
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      await saveSearch(credential, searchName, status, slaState, idempotencyKey);
      setSearchName("");
      setSavedSearches(await listSavedSearches(credential));
      setNotice({ kind: "success", message: "Private saved search created." });
    });
  }

  async function submitSimulation(event: FormEvent) {
    event.preventDefault();
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      const asOf = new Date().toISOString();
      const created = await createSimulation(credential, candidateVersionId,
        cohortStart, cohortEnd, asOf, includeSynthetic, idempotencyKey);
      setSimulation(created);
      setSimulationReport(null);
      setNotice({ kind: "success", message: "Historical simulation queued from an immutable candidate snapshot." });
    });
  }

  async function refreshSimulation() {
    if (!simulation) return;
    await run(async () => {
      const current = await getSimulation(credential, simulation.id);
      setSimulation(current);
      if (current.lifecycleStatus === "SUCCEEDED") {
        setSimulationReport(await getSimulationReport(credential, current.id));
      }
    });
  }

  async function changeControl(family: "rules" | "exceptions", id: string, transition: string) {
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      await transitionControl(credential, family, id, transition,
        transition === "activate" || transition === "rollback" ? activationRuns[id] : undefined,
        idempotencyKey);
      const [rules, exceptions] = await Promise.all([
        listGovernedRules(credential), listSafetyExceptions(credential),
      ]);
      setGovernedRules(rules);
      setSafetyExceptions(exceptions);
      setNotice({ kind: "success", message: `Governed control ${transition} recorded.` });
    });
  }

  async function reviseDispute(dispute: DisputeWorkflow) {
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      await reviseDisputeEvidence(credential, dispute.chargebackId, true, true, true,
        idempotencyKey);
      setDisputes(await listDisputes(credential));
      setNotice({ kind: "success", message: "Immutable dispute evidence version recorded." });
    });
  }

  async function submitReadyDispute(dispute: DisputeWorkflow) {
    if (dispute.latestEvidenceVersion === null) return;
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      await submitDispute(credential, dispute.chargebackId, dispute.latestEvidenceVersion!,
        idempotencyKey);
      setDisputes(await listDisputes(credential));
      setNotice({ kind: "success", message: "Latest ready evidence version submitted." });
    });
  }

  async function refreshDeadLetters() {
    await run(async () => setDeadLetters(await listWebhookDeadLetters(credential)));
  }

  async function redeliverDeadLetter(deliveryId: string) {
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      const scheduled = await redeliverWebhook(credential, deliveryId, idempotencyKey);
      setDeadLetters(await listWebhookDeadLetters(credential));
      setNotice({ kind: "success", message: `Generation ${scheduled.requestedGeneration} redelivery scheduled.` });
    });
  }

  async function showDeveloperCatalogue() {
    await run(async () => setDeveloperCatalogue(await loadDeveloperCatalogue()));
  }

  async function startDeveloperScenario(event: FormEvent) {
    event.preventDefault();
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      const created = await createDeveloperScenario(developerApiKey, scenarioRunnerToken,
        scenarioType, scenarioSeed, idempotencyKey);
      setDeveloperRun(created);
      setDeveloperReport(null);
      setNotice({ kind: "success", message: "Trusted scenario created from a deterministic specification." });
    });
  }

  async function executeNextDeveloperStep() {
    if (!developerRun) return;
    const claimKey = `scenario-claim-${developerRun.id}-${developerRun.nextStepIndex}-${developerWorkerId}`;
    await run(async () => {
      const claimed = await claimDeveloperScenario(developerApiKey, scenarioRunnerToken,
        developerRun.id, developerWorkerId, claimKey);
      if (!claimed.nextStep) {
        setDeveloperRun(claimed);
        return;
      }
      const resourceId = await executeDeveloperScenarioStep(developerApiKey,
        scenarioRunnerToken, claimed.nextStep);
      const advanced = await completeDeveloperScenarioStep(developerApiKey,
        scenarioRunnerToken, claimed.id, claimed.nextStep.stepIndex,
        developerWorkerId, claimed.leaseEpoch, resourceId,
        `scenario-complete-${claimed.id}-${claimed.nextStep.stepIndex}-${claimed.leaseEpoch}-${resourceId}`);
      setDeveloperRun(advanced);
      if (advanced.lifecycleStatus === "SUCCEEDED") {
        setDeveloperReport(await getDeveloperScenarioReport(developerApiKey,
          scenarioRunnerToken, advanced.id));
      }
      setNotice({ kind: "success", message: advanced.lifecycleStatus === "SUCCEEDED"
        ? "Scenario succeeded; sanitized report verified." : "Verified step committed. Continue when ready." });
    });
  }

  async function submitWebhookVerification(event: FormEvent) {
    event.preventDefault();
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      const challenge = await verifyWebhookEndpoint(
        developerApiKey, webhookEndpointId, idempotencyKey);
      setWebhookVerification(challenge);
      setNotice({ kind: "success", message: `Endpoint verification is ${challenge.lifecycleStatus.toLowerCase()}.` });
    });
  }

  function clearDeveloperCredentials() {
    setDeveloperApiKey("");
    setScenarioRunnerToken("");
    setDeveloperRun(null);
    setDeveloperReport(null);
    setNotice({ kind: "success", message: "Developer credentials and run view cleared from memory." });
  }

  function disconnect() {
    setCredential("");
    setNoteDraft("");
    setSupersedesNoteId(null);
    setPage(null);
    setSelected(null);
    setNotes([]);
    setSavedSearches([]);
    setCandidateVersionId("");
    setCohortStart("");
    setCohortEnd("");
    setSimulation(null);
    setSimulationReport(null);
    setGovernedRules([]);
    setSafetyExceptions([]);
    setActivationRuns({});
    setDisputes([]);
    setDeadLetters(null);
    setOperatorSession(null);
    setMonitoring(null);
    setMonitoringCohort(null);
    setSliceFailures({});
    queryClient.clear();
    setNotice({ kind: "success", message: "Workspace credential cleared from memory." });
  }

  const activeNotes = notes.filter((note) => note.lifecycleStatus === "ACTIVE");

  async function redact(noteId: string) {
    if (!selected) return;
    const idempotencyKey = crypto.randomUUID();
    await run(async () => {
      await redactNote(credential, selected.id, noteId, idempotencyKey);
      setNotes(await listNotes(credential, selected.id, selected.assignmentVersion));
      setNotice({ kind: "success", message: "Protected note content destroyed; lifecycle metadata retained." });
    });
  }

  const canAnalyze = operatorSession?.role === "ANALYST" || operatorSession?.role === "RISK_MANAGER";
  const canManage = operatorSession?.role === "RISK_MANAGER";

  return (
    <div className="app-shell">
      <header className="masthead">
        <div>
          <p className="eyebrow">THAAP / RISK OPERATIONS</p>
          <h1 ref={headingRef} tabIndex={-1}>Analyst cockpit</h1>
          <p className="lede">Explainable review queues, durable context, and explicit risk truth.</p>
        </div>
        {operatorSession && <div className="session-actions">
          {operatorSession && <span className="pill" aria-label="Signed-in operator role">
            {operatorSession.role.replace("_", " ")}
          </span>}
          <button className="quiet" onClick={disconnect}>Disconnect</button>
        </div>}
      </header>

      {notice && <div className={`notice ${notice.kind}`} role={notice.kind === "error" ? "alert" : "status"}
        data-failure-state={notice.state}>
        <span>{notice.message}</span>
        {notice.kind === "error" && retryTask && <button className="quiet" disabled={busy}
          onClick={() => void run(retryTask)}>Retry same safe request</button>}
      </div>}

      {!operatorSession ? (
        <main className="connect-panel">
          <section className="card intro-card">
            <span className="section-index">01</span>
            <h2>Enter the risk workspace</h2>
            <p>The operator credential is held in this page’s memory and is never written to browser storage.</p>
            <form onSubmit={connect} className="stack">
              <label htmlFor="credential">Risk operator credential</label>
              <input id="credential" type="password" autoComplete="off" value={credential}
                onChange={(event) => setCredential(event.target.value)} required />
              <button disabled={busy || !credential}>{busy ? "Connecting…" : "Open workspace"}</button>
            </form>
          </section>
          <section className="card developer-card" aria-labelledby="developer-heading">
            <span className="section-index">02 / DEVELOPER</span>
            <h2 id="developer-heading">Trusted scenario console</h2>
            <p>Use simulator-only cards and a separately issued runner credential. Both credentials remain in memory.</p>
            <form onSubmit={startDeveloperScenario} className="stack">
              <label htmlFor="developer-api-key">Merchant API key</label>
              <input id="developer-api-key" type="password" autoComplete="off"
                value={developerApiKey} onChange={(event) => setDeveloperApiKey(event.target.value)} required />
              <label htmlFor="scenario-runner-token">Scenario runner credential</label>
              <input id="scenario-runner-token" type="password" autoComplete="off"
                value={scenarioRunnerToken} onChange={(event) => setScenarioRunnerToken(event.target.value)} required />
              <label htmlFor="scenario-type">Scenario</label>
              <select id="scenario-type" value={scenarioType}
                onChange={(event) => setScenarioType(event.target.value as DeveloperScenarioRun["scenarioType"])}>
                <option value="APPROVED_PAYMENT">Approved payment</option>
                <option value="ISSUER_DECLINE">Issuer decline</option>
                <option value="CARD_TESTING_BURST">Card-testing burst</option>
              </select>
              <label htmlFor="scenario-seed">Deterministic seed</label>
              <input id="scenario-seed" pattern="[A-Za-z0-9._-]+" maxLength={64}
                value={scenarioSeed} onChange={(event) => setScenarioSeed(event.target.value)} required />
              <div className="developer-actions">
                <button disabled={busy || !developerApiKey || !scenarioRunnerToken}>Create or resume</button>
                <button type="button" className="secondary" disabled={busy}
                  onClick={() => void showDeveloperCatalogue()}>Load test-card catalogue</button>
              </div>
            </form>
            {developerRun && <div className="developer-progress" aria-live="polite">
              <p><span className="pill">{developerRun.lifecycleStatus}</span>{" "}
                {developerRun.nextStepIndex} / {developerRun.totalSteps} verified steps</p>
              <p>{developerRun.nextStep
                ? `Next: ${developerRun.nextStep.operation.replaceAll("_", " ")}`
                : "No step remains."}</p>
              <code>{developerRun.specificationDigest}</code>
              {developerRun.nextStep && <button disabled={busy}
                onClick={() => void executeNextDeveloperStep()}>Execute next verified step</button>}
              {developerReport && <div className="report-summary">
                <h3>Sanitized report</h3>
                <p>Origin: {developerReport.report.trafficOrigin}</p>
                <p>Outcomes: {Object.entries(developerReport.report.outcomeBreakdown)
                  .map(([outcome, count]) => `${outcome} ${count}`).join(" · ")}</p>
                <p>Traffic: {Object.entries(developerReport.report.originBreakdown)
                  .map(([origin, count]) => `${origin} ${count}`).join(" · ")}</p>
                <p className="muted">Digest {developerReport.reportDigest}</p>
              </div>}
              <button type="button" className="quiet" onClick={clearDeveloperCredentials}>Clear developer credentials</button>
            </div>}
            {developerCatalogue && <div className="catalogue-table table-wrap">
              <h3>Simulator-only test cards</h3>
              <table><thead><tr><th>Fixture</th><th>Test card</th><th>Outcome</th></tr></thead>
                <tbody>{developerCatalogue.testCards.map((card) => <tr key={card.fixtureId}>
                  <td>{card.fixtureId}</td><td><code>{card.cardNumber}</code></td>
                  <td>{card.processorOutcomeCode}<small>{card.usage.replace("_", " ")}</small></td>
                </tr>)}</tbody></table>
            </div>}
            <form onSubmit={submitWebhookVerification} className="stack verification-form">
              <h3>Verify a webhook endpoint</h3>
              <p className="muted">Paste an endpoint ID created with this merchant API key. Retrying after an ambiguous network failure reuses the same in-memory idempotency key and returns the same challenge.</p>
              <label htmlFor="webhook-endpoint-id">Webhook endpoint ID</label>
              <input id="webhook-endpoint-id" value={webhookEndpointId}
                onChange={(event) => setWebhookEndpointId(event.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000" required />
              <button disabled={busy || !developerApiKey || !webhookEndpointId}>Start verification</button>
            </form>
            {webhookVerification && <div className="report-summary" aria-live="polite">
              <h3>Verification challenge</h3>
              <p><span className="pill">{webhookVerification.lifecycleStatus}</span></p>
              <p>Expires {new Date(webhookVerification.expiresAt).toISOString()}</p>
              {webhookVerification.responseStatus !== null && <p>Receiver status {webhookVerification.responseStatus}</p>}
              {webhookVerification.failureCode && <p>Failure {webhookVerification.failureCode.replaceAll("_", " ")}</p>}
              <p className="muted">Challenge {webhookVerification.id}</p>
            </div>}
          </section>
        </main>
      ) : (
        <main className="workspace-grid">
          <aside className="card filter-rail" aria-label="Queue controls">
            <span className="section-index">01 / FILTER</span>
            <label htmlFor="status">Review status</label>
            <select id="status" value={status} onChange={(event) => setStatus(event.target.value)}>
              <option value="">All statuses</option><option>OPEN</option><option>ASSIGNED</option>
              <option>RESOLVED</option><option>CANCELED</option>
            </select>
            <label htmlFor="sla">SLA state</label>
            <select id="sla" value={slaState} onChange={(event) => setSlaState(event.target.value)}>
              <option value="">All SLA states</option><option>BREACHED</option><option>WITHIN_SLA</option>
            </select>
            <button disabled={busy || Boolean(sliceFailures.reviews)}
              onClick={() => void refreshQueue()}>Apply filter</button>
            <hr />
            <form onSubmit={submitSavedSearch} className="stack">
              <label htmlFor="search-name">Save current view</label>
              <input id="search-name" maxLength={80} value={searchName}
                onChange={(event) => setSearchName(event.target.value)} placeholder="Priority review queue" required />
              <button className="secondary"
                disabled={busy || Boolean(sliceFailures.savedSearches)}>Save privately</button>
            </form>
            <div className="saved-list">
              <h3>Saved searches</h3>
              <SliceFailureNotice label="Saved searches" failure={sliceFailures.savedSearches}
                busy={busy} retry={() => void retryWorkspaceSlice("savedSearches")} />
              {!sliceFailures.savedSearches && savedSearches.length === 0
                && <p className="muted">No saved searches.</p>}
              {savedSearches.map((search) => (
                <button key={search.id} className="saved-search" onClick={() => void run(async () => {
                  setPage(await executeSavedSearch(credential, search.id));
                  setSelected(null);
                })}>{search.name}<small>{search.visibility.replace("_", " ")}</small></button>
              ))}
            </div>
          </aside>

          <section className="card queue-panel" aria-labelledby="queue-heading">
            <div className="section-heading">
              <div><span className="section-index">02 / QUEUE</span><h2 id="queue-heading">Reviews</h2></div>
              <p>{page
                ? `${page.rows.length} visible · as of ${new Date(page.asOf).toLocaleTimeString()}`
                : "Review queue unavailable"}</p>
            </div>
            <SliceFailureNotice label="Review queue" failure={sliceFailures.reviews}
              busy={busy} retry={() => void retryWorkspaceSlice("reviews")} />
            {page && <div className="table-wrap">
              <table>
                <thead><tr><th>Created</th><th>Status</th><th>SLA</th><th>Decision</th><th>Reasons</th></tr></thead>
                <tbody>
                  {page.rows.map((row) => (
                    <tr key={row.id} className={selected?.id === row.id ? "selected" : ""}>
                      <td><button className="row-link" onClick={() => setSelected(row)} aria-label={`Open review ${row.id}`}>{new Date(row.createdAt).toLocaleString()}</button></td>
                      <td>{row.status}</td><td><span className={`pill ${row.slaState === "BREACHED" ? "danger" : "ok"}`}>{row.slaState.replace("_", " ")}</span></td>
                      <td>{row.decisionAction}</td><td>{row.reasonCodes.join(", ")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>}
            {page?.nextCursor && <button className="secondary load-more" disabled={busy}
              onClick={() => void refreshQueue(page.nextCursor!)}>Load next page</button>}
          </section>

          <section className="card monitoring-panel" aria-labelledby="monitoring-heading">
            <div className="section-heading">
              <div><span className="section-index">04 / MONITOR</span><h2 id="monitoring-heading">Merchant risk health</h2></div>
              <p>{monitoring
                ? `Definition ${monitoring.definitionVersion} · as of ${new Date(monitoring.asOf).toLocaleString()}`
                : sliceFailures.monitoring ? "Monitoring unavailable" : "No monitoring result"}</p>
            </div>
            <div className="monitoring-actions">
              <label className="checkbox-label"><input type="checkbox" checked={monitoringIncludesSynthetic}
                onChange={(event) => {
                  setMonitoringIncludesSynthetic(event.target.checked);
                  void refreshMonitoring(event.target.checked);
                }} /> Include trusted synthetic traffic</label>
              <button className="secondary" disabled={busy}
                onClick={() => void refreshMonitoring()}>Refresh monitoring</button>
            </div>
            <SliceFailureNotice label="Monitoring" failure={sliceFailures.monitoring}
              busy={busy} retry={() => void retryWorkspaceSlice("monitoring")} />
            {!monitoring && !sliceFailures.monitoring
              && <p role="status">Monitoring summary has no available result.</p>}
            {monitoring?.metrics.length === 0 && <p className="muted" role="status">No monitoring definitions are available.</p>}
            {monitoring && <div className="table-wrap"><table>
              <thead><tr><th>Metric</th><th>Value</th><th>Evidence</th><th>Coverage</th><th>Lineage</th></tr></thead>
              <tbody>{monitoring.metrics.map((metric) => <tr key={metric.metricCode}>
                <td>{metric.metricCode.replaceAll("_", " ")}</td>
                <td>{metric.value === null ? "Not available" : `${metric.value} ${metric.unit.toLowerCase()}`}</td>
                <td>{metric.numerator} / {metric.denominator === null ? "not applicable" : metric.denominator}</td>
                <td><span className={`pill ${metric.coverageStatus === "COMPLETE" ? "ok" : "danger"}`}>{metric.coverageStatus}</span><small>{metric.observedCount} observed · {metric.eligibleCount} eligible</small></td>
                <td><button className="row-link" disabled={busy}
                  onClick={() => void drillIntoMetric(metric.metricCode)}>Inspect cohort</button></td>
              </tr>)}</tbody>
            </table></div>}
            {monitoringCohort && <div className="cohort-detail" aria-live="polite">
              <h3>{monitoringCohort.metricCode.replaceAll("_", " ")} cohort evidence</h3>
              {monitoringCohort.rows.length === 0
                ? <p className="muted">No resources belong to this exact metric window.</p>
                : <div className="table-wrap"><table><thead><tr><th>Occurred</th><th>Resource</th><th>Membership</th><th>Evidence</th><th>Count</th></tr></thead>
                  <tbody>{monitoringCohort.rows.map((row) => <tr key={`${row.resourceId}-${row.evidenceCode}`}>
                    <td>{new Date(row.occurredAt).toLocaleString()}</td><td>{row.resourceType}<small>{row.resourceId}</small></td>
                    <td>N {row.numeratorMember ? "yes" : "no"} · D {row.denominatorMember ? "yes" : "no"}</td>
                    <td>{row.evidenceCode.replaceAll("_", " ")}</td><td>{row.count}</td>
                  </tr>)}</tbody></table></div>}
            </div>}
          </section>

          <aside className="card detail-panel" aria-labelledby="detail-heading">
            <span className="section-index">03 / CASE</span>
            {!selected ? <div className="empty-state"><h2 id="detail-heading">
              {sliceFailures.reviews ? "Review detail unavailable" : "Select a review"}</h2>
              <p>{sliceFailures.reviews
                ? "Restore the review queue to inspect protected analyst context."
                : "Open a queue row to inspect lineage and protected analyst context."}</p></div> : <>
              <h2 id="detail-heading">Review detail</h2>
              <dl><dt>Review ID</dt><dd>{selected.id}</dd><dt>Payment intent</dt><dd>{selected.paymentIntentId}</dd><dt>Assignment version</dt><dd>{selected.assignmentVersion}</dd></dl>
              <h3>Protected notes</h3>
              <div className="notes" aria-live="polite">
                {notes.length === 0 && <p className="muted">No analyst context recorded.</p>}
                {notes.map((note) => <article key={note.id} className="note"><time>{new Date(note.createdAt).toLocaleString()}</time><span className="pill">{note.lifecycleStatus}</span><p>{note.content ?? "Content destroyed by lifecycle policy."}</p>{note.lifecycleStatus === "ACTIVE" && canAnalyze && <div className="note-actions"><button className="secondary" type="button" onClick={() => { setSupersedesNoteId(note.id); setNoteDraft(""); }}>Correct</button>{canManage && <button className="secondary danger-action" type="button" onClick={() => void redact(note.id)}>Manager redact</button>}</div>}</article>)}
              </div>
              {canAnalyze ? <form onSubmit={submitNote} className="stack note-form">
                <label htmlFor="note">{supersedesNoteId ? "Correct protected context" : "Add protected context"}</label>
                {supersedesNoteId && <div className="correction-banner">Correction creates a new append-only note. <button type="button" className="quiet" onClick={() => setSupersedesNoteId(null)}>Cancel</button></div>}
                <textarea id="note" maxLength={2000} rows={5} value={noteDraft} onChange={(event) => setNoteDraft(event.target.value)} required />
                <div className="form-meta"><span>{Array.from(noteDraft).length} / 2,000</span><button disabled={busy || !noteDraft.trim()}>{supersedesNoteId ? "Record correction" : "Record note"}</button></div>
              </form> : <p className="muted" role="status">Viewer access is read-only; analyst notes and corrections are hidden.</p>}
              <p className="privacy-note">Notes are encrypted before persistence. Never enter PAN, CVV, credentials, or secrets.</p>
              <p className="muted">{activeNotes.length} active note{activeNotes.length === 1 ? "" : "s"}</p>
            </>}
          </aside>

          <section className="card simulation-panel" aria-labelledby="simulation-heading">
            <div className="section-heading">
              <div><span className="section-index">04 / SIMULATE</span><h2 id="simulation-heading">Historical policy evidence</h2></div>
              <p>UTC half-open cohort · decision-time features only</p>
            </div>
            {canManage ? <form onSubmit={submitSimulation} className="simulation-form">
              <label htmlFor="candidate-version">Candidate policy version ID</label>
              <input id="candidate-version" value={candidateVersionId}
                onChange={(event) => setCandidateVersionId(event.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000" required />
              <label htmlFor="cohort-start">Cohort start (UTC ISO-8601)</label>
              <input id="cohort-start" value={cohortStart}
                onChange={(event) => setCohortStart(event.target.value)}
                placeholder="2026-07-24T00:00:00.000000Z" required />
              <label htmlFor="cohort-end">Cohort end (UTC ISO-8601)</label>
              <input id="cohort-end" value={cohortEnd}
                onChange={(event) => setCohortEnd(event.target.value)}
                placeholder="2026-07-25T00:00:00.000000Z" required />
              <label className="checkbox-label"><input type="checkbox" checked={includeSynthetic}
                onChange={(event) => setIncludeSynthetic(event.target.checked)} /> Include trusted synthetic scenarios</label>
              <button disabled={busy}>Queue simulation</button>
            </form> : <p className="muted" role="status">Historical simulation mutations require risk-manager access.</p>}
            {simulation && <div className="simulation-result" aria-live="polite">
              <div><span className="pill">{simulation.lifecycleStatus}</span><strong>{simulation.processedCount} processed</strong><span>{simulation.eligibleCount} eligible · {simulation.excludedCount} excluded</span></div>
              <button className="secondary" disabled={busy} onClick={() => void refreshSimulation()}>Refresh durable progress</button>
              <dl><dt>Run</dt><dd>{simulation.id}</dd><dt>Candidate digest</dt><dd>{simulation.candidateDigest}</dd></dl>
              {simulationReport && <div className="report-summary">
                <h3>Immutable report</h3>
                <p>Transitions: {Object.entries(simulationReport.report.actionTransitions)
                  .map(([transition, count]) => `${transition} ${count}`).join(" · ") || "none"}</p>
                <p>Labelled coverage: {simulationReport.report.labelledCount} / {simulationReport.report.population.eligible}</p>
                <p>Protected suppression attempts: {simulationReport.report.protectedSuppressionCount}</p>
                <p className="muted">Digest {simulationReport.reportDigest} · expires {new Date(simulationReport.expiresAt).toISOString()}</p>
              </div>}
            </div>}
          </section>

          <section className="card controls-panel" aria-labelledby="controls-heading">
            <div className="section-heading">
              <div><span className="section-index">05 / GOVERN</span><h2 id="controls-heading">Rule and exception approvals</h2></div>
              <p>Immutable versions · dual control · exact simulation gate</p>
            </div>
            <SliceFailureNotice label="Governed rules" failure={sliceFailures.rules}
              busy={busy} retry={() => void retryWorkspaceSlice("rules")} />
            <SliceFailureNotice label="Safety exceptions" failure={sliceFailures.exceptions}
              busy={busy} retry={() => void retryWorkspaceSlice("exceptions")} />
            {!sliceFailures.rules && !sliceFailures.exceptions
              && governedRules.length === 0 && safetyExceptions.length === 0
              && <p className="muted">No governed controls are awaiting operation.</p>}
            <div className="control-grid">
              {governedRules.map((rule) => <article className="control-card" key={rule.id}>
                <span className="pill">RULE / {rule.lifecycleStatus}</span>
                <h3>{rule.ruleCode} v{rule.versionNumber}</h3>
                <p>{rule.displayName} · emits {rule.candidateAction}</p>
                <code>{rule.ruleDigest}</code>
                <ControlActions id={rule.id} state={rule.lifecycleStatus} family="rules"
                  runId={activationRuns[rule.id] ?? ""}
                  setRunId={(value) => setActivationRuns({ ...activationRuns, [rule.id]: value })}
                  act={changeControl} busy={busy} canManage={canManage} />
              </article>)}
              {safetyExceptions.map((exception) => <article className="control-card" key={exception.id}>
                <span className="pill">EXCEPTION / {exception.lifecycleStatus}</span>
                <h3>{exception.exceptionCode} v{exception.versionNumber}</h3>
                <p>Suppresses only {exception.sourceRuleCode} · {exception.reasonCode.replaceAll("_", " ")}</p>
                <p className="muted">Expires {new Date(exception.expiresAt).toISOString()}</p>
                <ControlActions id={exception.id} state={exception.lifecycleStatus} family="exceptions"
                  runId={activationRuns[exception.id] ?? ""}
                  setRunId={(value) => setActivationRuns({ ...activationRuns, [exception.id]: value })}
                  act={changeControl} busy={busy} canManage={canManage} />
              </article>)}
            </div>
          </section>

          <section className="card controls-panel" aria-labelledby="disputes-heading">
            <div className="section-heading">
              <div><span className="section-index">06 / DISPUTES</span><h2 id="disputes-heading">Deadline and outcome operations</h2></div>
              <p>Governed UTC deadlines · immutable evidence · manager submission</p>
            </div>
            <SliceFailureNotice label="Disputes" failure={sliceFailures.disputes}
              busy={busy} retry={() => void retryWorkspaceSlice("disputes")} />
            {!sliceFailures.disputes && disputes.length === 0
              && <p className="muted">No dispute workflows are open.</p>}
            <div className="control-grid">
              {disputes.map((dispute) => <article className="control-card" key={dispute.id}>
                <span className={`pill ${dispute.lifecycleStatus === "EXPIRED" ? "danger" : ""}`}>
                  {dispute.lifecycleStatus} / {dispute.readinessStatus}
                </span>
                <h3>{dispute.reasonCode.replaceAll("_", " ")}</h3>
                <p>Due {new Date(dispute.dueAt).toISOString()}</p>
                <p>{dispute.missingRequirements.length === 0
                  ? "All governed requirements satisfied."
                  : `Missing: ${dispute.missingRequirements.join(", ").replaceAll("_", " ")}`}</p>
                {dispute.evidenceDigest && <code>{dispute.evidenceDigest}</code>}
                {canManage && (dispute.lifecycleStatus === "DRAFT" || dispute.lifecycleStatus === "READY")
                  && <button className="secondary" disabled={busy}
                    onClick={() => void reviseDispute(dispute)}>Record closed evidence facts</button>}
                {canManage && dispute.lifecycleStatus === "READY" && dispute.latestEvidenceVersion !== null
                  && <button disabled={busy} onClick={() => void submitReadyDispute(dispute)}>
                    Submit version {dispute.latestEvidenceVersion}</button>}
              </article>)}
            </div>
          </section>

          <section className="card controls-panel" aria-labelledby="dead-letters-heading">
            <div className="section-heading">
              <div><span className="section-index">07 / WEBHOOKS</span><h2 id="dead-letters-heading">Webhook dead letters</h2></div>
              <p>Terminal deliveries · exact event replay · durable generation state</p>
            </div>
            {canManage ? <button className="secondary" disabled={busy}
              onClick={() => void refreshDeadLetters()}>Refresh dead letters</button>
              : <p className="muted" role="status">Dead-letter mutation controls require risk-manager access.</p>}
            {deadLetters === null && <p className="muted">Load the bounded dead-letter view when operational review is needed.</p>}
            {deadLetters?.length === 0 && <p className="muted" role="status">No exhausted webhook deliveries.</p>}
            <div className="control-grid" aria-live="polite">
              {deadLetters?.map((delivery) => <article className="control-card" key={delivery.id}>
                <span className="pill danger">EXHAUSTED / GENERATION {delivery.deliveryGeneration}</span>
                <h3>{delivery.eventType}</h3>
                <p>{delivery.errorCode.replaceAll("_", " ")} · {delivery.attemptCount} total attempts</p>
                <p className="muted">Last attempt {delivery.lastAttemptAt
                  ? new Date(delivery.lastAttemptAt).toISOString() : "not recorded"}</p>
                {canManage && <button disabled={busy} onClick={() => void redeliverDeadLetter(delivery.id)}>
                  Redeliver exact event
                </button>}
              </article>)}
            </div>
          </section>
        </main>
      )}
    </div>
  );
}

function workspaceSliceLabel(slice: WorkspaceSlice): string {
  const labels: Record<WorkspaceSlice, string> = {
    monitoring: "Monitoring",
    reviews: "Review queue",
    savedSearches: "Saved searches",
    rules: "Governed rules",
    exceptions: "Safety exceptions",
    disputes: "Disputes",
  };
  return labels[slice];
}

function SliceFailureNotice({ label, failure, retry, busy }: {
  label: string; failure: SliceFailure | undefined; retry: () => void; busy: boolean;
}) {
  if (!failure) return null;
  return <div className="slice-failure" role="alert" data-failure-state={failure.state}>
    <p><strong>{label} unavailable.</strong> {failure.message}</p>
    {failure.state !== "denied" && <button type="button" className="quiet" disabled={busy}
      onClick={retry}>Retry {label.toLowerCase()}</button>}
  </div>;
}

function ControlActions({ id, state, family, runId, setRunId, act, busy, canManage }: {
  id: string; state: string; family: "rules" | "exceptions"; runId: string;
  setRunId: (value: string) => void;
  act: (family: "rules" | "exceptions", id: string, transition: string) => Promise<void>;
  busy: boolean; canManage: boolean;
}) {
  if (!canManage) return <p className="muted">Manager actions hidden.</p>;
  if (state === "DRAFT") return <button disabled={busy} onClick={() => void act(family, id, "submit")}>Submit</button>;
  if (state === "SUBMITTED") return <button disabled={busy} onClick={() => void act(family, id, "approve")}>Approve as second manager</button>;
  if (state === "APPROVED" || (family === "rules" && state === "RETIRED")) return <div className="activation-control">
    <label htmlFor={`run-${id}`}>Successful simulation run ID</label>
    <input id={`run-${id}`} value={runId} onChange={(event) => setRunId(event.target.value)} />
    <button disabled={busy || !runId} onClick={() => void act(family, id,
      state === "RETIRED" ? "rollback" : "activate")}>{state === "RETIRED" ? "Rollback activate" : "Activate"}</button>
  </div>;
  if (state === "ACTIVE") return <button className="secondary" disabled={busy}
    onClick={() => void act(family, id, "retire")}>Retire</button>;
  return null;
}
