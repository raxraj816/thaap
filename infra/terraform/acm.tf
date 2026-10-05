# Two certificates for one hostname, because CloudFront and the ALB validate them in different
# places: a distribution's viewer certificate must live in the CloudFront control-plane region,
# and a load balancer's must live with the load balancer.
#
# Both validate by DNS through the same hosted zone, so both wait on the same records. This is the
# step that stalls a first deploy when the domain's nameservers have not finished repointing —
# the failure looks like Terraform hanging and is really DNS that has not propagated.

resource "aws_acm_certificate" "alb" {
  domain_name       = var.workspace_hostname
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_acm_certificate" "cloudfront" {
  provider = aws.cloudfront

  domain_name       = var.workspace_hostname
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# ACM's token for one account/hostname is the same across regions. There is exactly one
# hostname and one DNS resource. Computed record names must not be for_each keys: those keys
# are unknown during the first plan, and combining both certificates also produces duplicates.
resource "aws_route53_record" "certificate_validation" {
  zone_id         = var.hosted_zone_id
  name            = one(aws_acm_certificate.alb.domain_validation_options).resource_record_name
  type            = one(aws_acm_certificate.alb.domain_validation_options).resource_record_type
  records         = [one(aws_acm_certificate.alb.domain_validation_options).resource_record_value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "alb" {
  certificate_arn         = aws_acm_certificate.alb.arn
  validation_record_fqdns = [aws_route53_record.certificate_validation.fqdn]
}

resource "aws_acm_certificate_validation" "cloudfront" {
  provider = aws.cloudfront

  certificate_arn         = aws_acm_certificate.cloudfront.arn
  validation_record_fqdns = [aws_route53_record.certificate_validation.fqdn]
}
