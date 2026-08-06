import { useEffect, useState } from "react";
import {
  ApiFailure,
  ApiVersionCatalogue,
  PublicErrorCatalogue,
  getApiVersionCatalogue,
  getPublicErrorCatalogue,
} from "./api";

type Props = { path: string; navigate: (path: string) => void };

const CURRENT_VERSION = "2026-08-01";

export function DeveloperPortal({ path, navigate }: Props) {
  return <main className="phase-workspace developer-portal" aria-labelledby="developer-portal-heading">
    <section className="card phase-hero">
      <span className="section-index">DEVELOPER SANDBOX</span>
      <h2 id="developer-portal-heading">Build against a versioned, replay-safe contract.</h2>
      <p className="muted">The local sandbox, generated Python client, test fixtures, and webhook verifier share one public contract. Credentials stay out of URLs, examples, browser storage, and copied diagnostics.</p>
      <nav className="developer-route-nav" aria-label="Developer portal">
        <RouteLink path="/developer/quickstart" current={path} navigate={navigate}>Quickstart</RouteLink>
        <RouteLink path="/developer/api" current={path} navigate={navigate}>API contract</RouteLink>
        <RouteLink path="/developer/webhooks" current={path} navigate={navigate}>Webhooks</RouteLink>
        <RouteLink path="/developer/scenarios" current={path} navigate={navigate}>Scenarios</RouteLink>
      </nav>
    </section>
    {path === "/developer/api" ? <ApiRoute />
      : path === "/developer/webhooks" ? <WebhookRoute />
      : path === "/developer/scenarios" ? <ScenarioRoute navigate={navigate} />
      : <QuickstartRoute navigate={navigate} />}
  </main>;
}

function RouteLink({ path, current, navigate, children }: {
  path: string; current: string; navigate: Props["navigate"]; children: string;
}) {
  return <a href={path} aria-current={path === current ? "page" : undefined}
    onClick={(event) => { event.preventDefault(); navigate(path); }}>{children}</a>;
}

function QuickstartRoute({ navigate }: Pick<Props, "navigate">) {
  return <section className="card developer-route-card" aria-labelledby="quickstart-heading">
    <h3 id="quickstart-heading">Python quickstart</h3>
    <ol className="quickstart-steps">
      <li>Install the committed client in an isolated environment.</li>
      <li>Put the merchant API key in process memory through <code>THAAP_MERCHANT_API_KEY</code>.</li>
      <li>Run the deterministic sandbox journey with an explicit local base URL.</li>
      <li>Reuse the same idempotency key and identical body after an ambiguous mutation result.</li>
    </ol>
    <pre aria-label="Python SDK quickstart command"><code>{`python -m venv .venv
. .venv/bin/activate
pip install ./clients/python/thaap_sdk
export THAAP_MERCHANT_API_KEY="<read from your secret manager>"
export THAAP_BASE_URL="http://127.0.0.1:8080"
python clients/python/thaap_sdk/examples/sandbox_quickstart.py`}</code></pre>
    <p className="muted">The reviewed wrapper rejects redirects, proxy inheritance, unsafe schemes, and non-loopback plain HTTP. It maps structured errors and treats ambiguous mutation outcomes separately from confirmed API failures.</p>
    <button onClick={() => navigate("/developer/api")}>Inspect the exact contract</button>
  </section>;
}

function ApiRoute() {
  const [versions, setVersions] = useState<ApiVersionCatalogue | null>(null);
  const [errors, setErrors] = useState<PublicErrorCatalogue | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);

  async function load() {
    setFailure(null);
    try {
      const [versionResult, errorResult] = await Promise.all([
        getApiVersionCatalogue(), getPublicErrorCatalogue(),
      ]);
      setVersions(versionResult);
      setErrors(errorResult);
    } catch (error) {
      setFailure(error as ApiFailure);
    }
  }

  useEffect(() => { void load(); }, []);

  return <section className="card developer-route-card" aria-labelledby="api-contract-heading">
    <div className="section-heading"><div><h3 id="api-contract-heading">Public API contract</h3>
      <p>OpenAPI 3.0 · date version · stable machine errors</p></div>
      <a className="button-link" href={`/developer/openapi/${CURRENT_VERSION}`}
        referrerPolicy="no-referrer">Download OpenAPI</a></div>
    {failure && <div role="alert" className="slice-failure"><strong>Contract metadata unavailable.</strong>
      <p>{failure.message}{failure.requestId ? ` (request ${failure.requestId})` : ""}</p>
      <button onClick={() => void load()}>Retry metadata</button></div>}
    {!failure && (!versions || !errors) && <p role="status">Loading contract metadata…</p>}
    {versions && <div><h4>Supported versions</h4><div className="table-wrap"><table>
      <thead><tr><th>Version</th><th>Status</th><th>Introduced</th><th>Compatibility</th></tr></thead>
      <tbody>{versions.versions.map((version) => <tr key={version.version}>
        <td><code>{version.version}</code></td><td>{version.status}</td>
        <td>{version.introducedAt}</td><td>{version.compatibility}</td>
      </tr>)}</tbody></table></div></div>}
    {errors && <div><h4>Stable errors and retries</h4><div className="table-wrap"><table>
      <thead><tr><th>Code</th><th>HTTP</th><th>Retryable</th><th>Recovery</th></tr></thead>
      <tbody>{errors.errors.map((error) => <tr key={error.code}>
        <td><code>{error.code}</code></td><td>{error.httpStatus}</td>
        <td>{error.retryable ? "Yes — same idempotency key/body" : "No"}</td>
        <td>{error.recoveryAction.replaceAll("_", " ")}</td>
      </tr>)}</tbody></table></div></div>}
  </section>;
}

function WebhookRoute() {
  return <section className="card developer-route-card" aria-labelledby="webhook-guide-heading">
    <h3 id="webhook-guide-heading">Verify before delivery; authenticate exact bytes.</h3>
    <div className="developer-guide-grid">
      <article><h4>1. Verify ownership</h4><p>Start endpoint verification with merchant authority. Return the bounded challenge successfully before the endpoint becomes eligible.</p></article>
      <article><h4>2. Verify the signature</h4><p>Compute the HMAC over the timestamp and the exact raw request body before JSON parsing. Reject stale timestamps.</p></article>
      <article><h4>3. Deduplicate durably</h4><p>Persist the event identifier before applying business effects. A delivery generation can retry the same immutable event.</p></article>
      <article><h4>4. Acknowledge after commit</h4><p>Return success only after the durable effect and dedupe record commit. Use the dead-letter workflow for exhausted transport attempts.</p></article>
    </div>
    <pre aria-label="Webhook verification pseudocode"><code>{`raw_body = request.body_bytes          # do not re-serialize JSON
timestamp = header("Thaap-Timestamp")
signature = header("Thaap-Signature")
event_id = header("Thaap-Event-Id")
verify_hmac(secret, timestamp, raw_body, signature)
reject_if_stale(timestamp)
apply_once_in_transaction(event_id, raw_body)`}</code></pre>
  </section>;
}

function ScenarioRoute({ navigate }: Pick<Props, "navigate">) {
  return <section className="card developer-route-card" aria-labelledby="scenario-guide-heading">
    <h3 id="scenario-guide-heading">Trusted deterministic scenarios</h3>
    <p>Scenario traffic is explicitly tagged <code>SYNTHETIC_SCENARIO</code>. It never silently enters live-only fraud metrics, and execution requires both merchant authority and the separately issued runner credential.</p>
    <p>The interactive console is available on the credential entry screen; it resumes durable runs, claims one fenced step at a time, and renders only sanitized terminal evidence.</p>
    <button onClick={() => navigate("/")}>Open the scenario console</button>
  </section>;
}
