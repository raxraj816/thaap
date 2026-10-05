output "workspace_url" {
  description = "The URL a person opens. Reachable only from operator_allowlist_cidrs."
  value       = "https://${var.workspace_hostname}"
}

output "cloudfront_distribution_id" {
  description = "Needed to invalidate the SPA cache after a workspace deploy."
  value       = aws_cloudfront_distribution.main.id
}

output "spa_bucket" {
  description = "Bucket the workspace bundle is synced into."
  value       = aws_s3_bucket.spa.bucket
}

output "alb_dns_name" {
  description = <<-EOT
    The origin's own hostname. Published deliberately: C63-SEC-001 requires proving that a request
    to this name without the CloudFront shared header is refused, and a proof needs the address it
    is testing.
  EOT
  value       = aws_lb.main.dns_name
}

output "ecr_repository_urls" {
  description = "Where the release workflow pushes each image."
  value = {
    payments_api         = aws_ecr_repository.payments_api.repository_url
    analyst_dashboard_ui = aws_ecr_repository.analyst_dashboard_ui.repository_url
  }
}

output "database_endpoint" {
  description = "RDS endpoint. Not publicly reachable; useful for the Chapter 65 restore rehearsal."
  value       = aws_db_instance.main.address
}

output "runtime_secret_ids" {
  description = <<-EOT
    The Secrets Manager entries an operator must populate before the first deploy. Terraform
    creates them empty on purpose: a value here would be written into the state file.
  EOT
  value       = { for variable, secret in aws_secretsmanager_secret.runtime : variable => secret.name }
}

output "ecs_cluster_name" {
  description = "Needed by the rollback and restart procedures in the Chapter 65 runbook."
  value       = aws_ecs_cluster.main.name
}
