locals {
  name = "thaap-${var.environment}"

  tags = {
    Project     = "thaap"
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # ------------------------------------------------------------------------------------------
  # The routing contract — Section 9
  # ------------------------------------------------------------------------------------------
  #
  # These seven prefixes are not a free-hand list. They must equal the keys of
  # `paymentsApiProxy` in apps/analyst-dashboard-ui/vite.config.ts and the `location` blocks in
  # apps/analyst-dashboard-ui/nginx.conf, exactly.
  #
  # `scripts/routing-contract-check.py` fails the build when any of the three diverge, and
  # `src/routingContract.test.ts` already holds the first two together. A prefix added to the dev
  # proxy and forgotten here is a feature that works on every developer machine and, in
  # production, is answered by the SPA's index.html with a 200 — which is why routing drift is
  # invisible to a status-code check and needs a structural one.
  api_path_prefixes = [
    "/risk",
    "/developer",
    "/customers",
    "/payment_methods",
    "/payment_intents",
    "/webhook_endpoints",
    "/webhook_deliveries",
    "/chargebacks",
    "/fraud",
    "/ledger",
    "/payment_attempts",
    "/post_authorization_signals",
    "/refunds",
    "/reviews",
    "/rules",
    "/simulator",
  ]

  # Operator surfaces remain authenticated by the API and restricted by the same edge WAF.
  # They are not workspace navigation entries, but must not be served as SPA HTML.
  operational_path_prefixes = ["/actuator", "/admin", "/internal", "/v3"]

  # CloudFront matches on patterns rather than prefixes, and each API prefix needs both forms:
  # the bare path, and everything beneath it. `/risk*` alone would also capture a future
  # `/risky`, so the two exact patterns are generated instead of one loose one.
  api_cache_behaviors = flatten([
    for prefix in concat(local.api_path_prefixes, local.operational_path_prefixes) : [prefix, "${prefix}/*"]
  ])

  # ------------------------------------------------------------------------------------------
  # The runtime secret contract — Section 8
  # ------------------------------------------------------------------------------------------
  #
  # One Secrets Manager entry per value RuntimeSecretContractGate refuses to start without. The
  # map is keyed by the environment variable the application reads, so the task definition and
  # the reference documentation cannot disagree about which secret feeds which variable.
  #
  # Terraform creates the entries and never their values: `aws_secretsmanager_secret_version`
  # here would put production key material into state and into a plan output. The operator writes
  # each value once, out of band, and `lifecycle.ignore_changes` keeps Terraform from reverting it.
  runtime_secrets = {
    THAAP_ADMIN_BOOTSTRAP_TOKEN                = "Merchant creation authority."
    THAAP_PLATFORM_BOOTSTRAP_TOKEN             = "Platform operator authority, across tenants."
    THAAP_SCENARIO_RUNNER_TOKEN                = "Trusted synthetic scenario origin."
    THAAP_HASH_PEPPER                          = "Merchant API key and credential hashing."
    THAAP_CORRELATION_PEPPER                   = "Stable fraud and PII correlation keys."
    THAAP_AUTHENTICATION_REPLAY_ENCRYPTION_KEY = "Authentication replay KEK."
    THAAP_WEBHOOK_SECRET_ENCRYPTION_KEY        = "Webhook signing secret KEK."
    THAAP_REVIEW_NOTE_ENCRYPTION_KEY           = "Analyst review note KEK."
  }
}
