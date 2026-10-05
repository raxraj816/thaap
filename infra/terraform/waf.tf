# The first of the three Section 5.1 exposure layers, and the reason the deployment can be called
# private at all.
#
# Without it the hostname is reachable by anyone who reads a Certificate Transparency log. ACM
# issues publicly-trusted certificates, publicly-trusted certificates are submitted to CT logs,
# and opting out is not an option — a certificate without CT proof is untrusted in Chrome and
# Safari. CT streams are scanned continuously, so a new hostname is probed within minutes of
# issuance. "We have not published the URL" is not a control; this is (ER-0106).
#
# CLOUDFRONT-scoped WebACLs live in the CloudFront control-plane region regardless of where the
# rest of the stack runs.

resource "aws_wafv2_ip_set" "operators" {
  provider = aws.cloudfront

  name               = "${local.name}-operators"
  description        = "Addresses permitted to reach THAAP at all"
  scope              = "CLOUDFRONT"
  ip_address_version = "IPV4"
  addresses          = var.operator_allowlist_cidrs
}

resource "aws_wafv2_web_acl" "main" {
  provider = aws.cloudfront

  name        = local.name
  description = "Private-by-mechanism exposure control (Section 5.1)"
  scope       = "CLOUDFRONT"

  # Default block. An allowlist whose default action is `allow` is a list of addresses that get
  # in slightly faster than everybody else.
  default_action {
    block {}
  }

  rule {
    name     = "operator-allowlist"
    priority = 1

    statement {
      ip_set_reference_statement {
        arn = aws_wafv2_ip_set.operators.arn
      }
    }

    action {
      allow {}
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "operator-allowlist"
      sampled_requests_enabled   = true
    }
  }

  # Bounds what a single allowlisted address can do. The allowlist answers "who", and this
  # answers "how much" — an operator whose laptop is compromised, or a demo script left looping,
  # should not be able to produce an invoice on its own.
  rule {
    name = "operator-rate-limit"
    # Allow is terminating. Rate blocking MUST be evaluated before the allowlist's allow.
    priority = 0

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    action {
      block {}
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "operator-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = local.name
    sampled_requests_enabled   = true
  }
}
