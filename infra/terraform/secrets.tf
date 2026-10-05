# Secrets Manager entries for the eight values RuntimeSecretContractGate refuses to start without,
# plus the CloudFront-to-ALB shared header that makes the origin unreachable by its own name.
#
# Terraform creates the entries and never their values. An `aws_secretsmanager_secret_version`
# carrying real key material would write that material into the state file and into every plan
# output — and this repository already refuses to let a secret reach an image layer, so letting
# one reach a state file would be the same mistake with a different medium.
#
# The operator writes each value once:
#
#   aws secretsmanager put-secret-value \
#     --secret-id thaap-prod/THAAP_HASH_PEPPER \
#     --secret-string "$(openssl rand -hex 32)"
#
# Five need `openssl rand -hex 32` and three need `openssl rand -base64 32`; the reference in
# docs/references/RUNTIME_CONFIGURATION_REFERENCE.md says which. All eight must differ from each
# other — the gate refuses reuse — and none may be a value published in this repository.

resource "aws_secretsmanager_secret" "runtime" {
  for_each = local.runtime_secrets

  name        = "${local.name}/${each.key}"
  description = each.value

  # Long enough to undo a mistaken destroy, short enough that a rotated secret's predecessor does
  # not linger for a month.
  recovery_window_in_days = 7
}

# The one secret Terraform both creates and populates, because no human ever needs to know it:
# it exists solely so the ALB can tell a CloudFront request from a direct one.
resource "random_password" "cloudfront_origin_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "cloudfront_origin" {
  name        = "${local.name}/CLOUDFRONT_ORIGIN_SECRET"
  description = "Shared header proving a request reached the ALB through CloudFront (Section 5.1)."
}

resource "aws_secretsmanager_secret_version" "cloudfront_origin" {
  secret_id     = aws_secretsmanager_secret.cloudfront_origin.id
  secret_string = random_password.cloudfront_origin_secret.result
}
