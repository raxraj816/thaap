# The hostname a person types.
#
# An alias record rather than a CNAME so the apex works if the workspace hostname ever becomes
# one, and so there is no second DNS lookup on every visit.

resource "aws_route53_record" "workspace" {
  zone_id = var.hosted_zone_id
  name    = var.workspace_hostname
  type    = "A"

  alias {
    name    = aws_cloudfront_distribution.main.domain_name
    zone_id = aws_cloudfront_distribution.main.hosted_zone_id

    # CloudFront reports its own health; a second evaluation here would only add a way for the
    # record to disappear while the distribution is fine.
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "workspace_ipv6" {
  zone_id = var.hosted_zone_id
  name    = var.workspace_hostname
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}
