# The single entry point. The SPA is served from S3 by default; the seven API prefixes go to the
# ALB. One hostname, one routing list.
#
# The behaviors below are generated from `local.api_cache_behaviors`, which is derived from
# `local.api_path_prefixes` — the same seven prefixes as the Vite dev proxy and nginx.
# `scripts/routing-contract-check.py` fails the build when the three diverge.
#
# Routing drift is worth the machinery because of how it fails: a prefix missing here is not a
# 502 or a 404, it is the default behavior serving index.html with a 200. The API call succeeds,
# returns HTML, and the workspace reports something unrelated. A status-code check cannot see it.

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

data "aws_cloudfront_origin_request_policy" "cors_s3" {
  name = "Managed-CORS-S3Origin"
}

resource "aws_cloudfront_origin_access_control" "spa" {
  name                              = "${local.name}-spa"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "edge_request" {
  name    = "${local.name}-edge-request"
  runtime = "cloudfront-js-2.0"
  publish = true
  code = templatefile("${path.module}/edge-request.js.tftpl", {
    prefixes = concat(local.api_path_prefixes, local.operational_path_prefixes)
  })
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = local.name
  default_root_object = "index.html"
  aliases             = [var.workspace_hostname]
  price_class         = "PriceClass_All"
  web_acl_id          = aws_wafv2_web_acl.main.arn

  origin {
    origin_id                = "spa"
    domain_name              = aws_s3_bucket.spa.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.spa.id
  }

  origin {
    origin_id   = "api"
    domain_name = aws_lb.main.dns_name

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }

    # Section 5.1, layer two. Without this the ALB is reachable by its own hostname and the WebACL
    # above protects nothing but the distribution.
    custom_header {
      name  = "X-Thaap-Origin-Verify"
      value = random_password.cloudfront_origin_secret.result
    }
  }

  # The shell must revalidate on deploy. Hashed assets have a separate immutable behavior.
  default_cache_behavior {
    target_origin_id       = "spa"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]

    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.cors_s3.id
    compress                 = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.edge_request.arn
    }
  }

  ordered_cache_behavior {
    path_pattern             = "/assets/*"
    target_origin_id         = "spa"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.optimized.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.cors_s3.id
    compress                 = true
  }

  # One behavior per generated pattern: the bare prefix and everything beneath it.
  dynamic "ordered_cache_behavior" {
    for_each = local.api_cache_behaviors

    content {
      path_pattern           = ordered_cache_behavior.value
      target_origin_id       = "api"
      viewer_protocol_policy = "https-only"
      allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods         = ["GET", "HEAD"]

      # Nothing on an API path may be cached. Every one of these routes is tenant-scoped and
      # credential-bearing, and a shared cache in front of them is a cross-tenant disclosure
      # waiting for two merchants to request the same URL.
      cache_policy_id = data.aws_cloudfront_cache_policy.disabled.id

      # Forwards the credential headers the API derives tenancy from — X-Api-Key,
      # X-Risk-Operator-Key, Idempotency-Key — untouched.
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
      compress                 = true

      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.edge_request.arn
      }
    }
  }

  restrictions {
    geo_restriction {
      # Geography is not the boundary; the operator allowlist is. A country restriction here
      # would read as a second control and enforce nothing the WebACL does not already.
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = { Name = local.name }
}
