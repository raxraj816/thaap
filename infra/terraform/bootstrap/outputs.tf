output "state_bucket" {
  description = "Pass to the main root as -backend-config=\"bucket=...\"."
  value       = aws_s3_bucket.state.bucket
}

output "lock_table" {
  description = "Pass to the main root as -backend-config=\"dynamodb_table=...\"."
  value       = aws_dynamodb_table.locks.name
}

output "backend_config" {
  description = "The full init command for the main root, ready to copy."
  value       = <<-EOT
    scripts/terraform.sh init \
      -backend-config="bucket=${aws_s3_bucket.state.bucket}" \
      -backend-config="dynamodb_table=${aws_dynamodb_table.locks.name}" \
      -backend-config="region=${var.region}" \
      -backend-config="key=thaap/${var.environment}/terraform.tfstate"
  EOT
}
