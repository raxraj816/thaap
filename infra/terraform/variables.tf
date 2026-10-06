# Every input to the topology.
#
# This is the one file permitted to contain a literal AWS region string, and
# `scripts/terraform-region-parameterisation-check.py` enforces that. THAAP is a global product;
# infrastructure that names one region in fifty places is the first thing a second region forces
# somebody to rewrite, and by then it is load-bearing.

variable "region" {
  description = "AWS region the stack is deployed into. Everything derives from this."
  type        = string
  default     = "ap-south-1"
}

variable "cloudfront_control_plane_region" {
  description = <<-EOT
    Region for the two resources CloudFront requires there: the viewer certificate and the
    CLOUDFRONT-scoped WAF WebACL. This is an AWS constraint, not a deployment choice, and must
    not be changed to match var.region — doing so makes the distribution fail to accept its own
    certificate.
  EOT
  type        = string
  default     = "us-east-1"

  validation {
    condition     = var.cloudfront_control_plane_region == "us-east-1"
    error_message = "CloudFront viewer certificates and CLOUDFRONT WAF require the CloudFront control-plane region."
  }
}

variable "environment" {
  description = "Environment name. Forms the resource prefix, so it must be short and stable."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,12}$", var.environment))
    error_message = "environment must be 2-13 lowercase alphanumeric or hyphen characters."
  }
}

variable "availability_zone_count" {
  description = <<-EOT
    How many availability zones the subnets span. Two is the minimum an ALB accepts, and is what
    this deployment uses: RDS is deliberately single-AZ (Section 13), so the second zone buys
    load-balancer validity rather than database availability. Recorded so nobody reads two
    subnets as two-zone durability.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.availability_zone_count >= 2 && var.availability_zone_count <= 4 && floor(var.availability_zone_count) == var.availability_zone_count
    error_message = "availability_zone_count must be between 2 and 4."
  }
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.40.0.0/16"
}

# --------------------------------------------------------------------------------------------
# DNS and exposure
# --------------------------------------------------------------------------------------------

variable "domain_name" {
  description = "Registered domain, e.g. example.com. Its Route53 hosted zone must already exist."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route53 hosted zone ID for domain_name."
  type        = string
}

variable "workspace_hostname" {
  description = <<-EOT
    Fully qualified hostname the workspace and the API are served from. One hostname serves both:
    CloudFront routes the seven API prefixes to the ALB and everything else to the SPA, which is
    what keeps the routing contract in Section 9 to a single list.
  EOT
  type        = string
}

variable "operator_allowlist_cidrs" {
  description = <<-EOT
    Source addresses permitted to reach the deployment at all, as CIDRs.

    This is the outermost of the three Section 5.1 layers, and the deployment is not private
    without it. An empty list is rejected rather than treated as "allow all": a WebACL that
    admits everybody while the plan calls the deployment invite-only is worse than no WebACL,
    because it reads as a control in every review that follows.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.operator_allowlist_cidrs) > 0
    error_message = "operator_allowlist_cidrs must not be empty; the deployment is private by mechanism, not by obscurity."
  }

  validation {
    condition     = alltrue([for cidr in var.operator_allowlist_cidrs : can(cidrnetmask(cidr)) && try(tonumber(split("/", cidr)[1]), 0) > 0])
    error_message = "operator_allowlist_cidrs must contain valid IPv4 CIDRs with a nonzero prefix; world-wide /0, IPv6 and malformed entries are forbidden."
  }
}

# --------------------------------------------------------------------------------------------
# Compute sizing
# --------------------------------------------------------------------------------------------

variable "api_desired_count" {
  description = <<-EOT
    Number of payments-api tasks. Two is safe without leader election: Flyway holds a migration
    lock and all eight @Scheduled workers coordinate through database leases with fencing epochs,
    proven by the Phase 5 restart suites.
  EOT
  type        = number
  default     = 0

  validation {
    condition     = var.api_desired_count >= 0 && floor(var.api_desired_count) == var.api_desired_count
    error_message = "api_desired_count must be a nonnegative integer; use 0 for foundation provisioning and 2 only after runtime secrets exist."
  }
}

variable "api_cpu" {
  description = "Fargate CPU units for payments-api. 1024 = 1 vCPU."
  type        = number
  default     = 1024
}

variable "api_memory" {
  description = "Fargate memory (MiB) for payments-api. The JVM bounds its heap to 75% of this."
  type        = number
  default     = 2048
}

variable "kafka_cpu" {
  description = "Fargate CPU units for the single KRaft broker."
  type        = number
  default     = 512
}

variable "kafka_memory" {
  description = "Fargate memory (MiB) for the single KRaft broker."
  type        = number
  default     = 1024
  validation {
    condition     = var.kafka_memory >= 1024 && floor(var.kafka_memory) == var.kafka_memory
    error_message = "Broker memory must be an integer of at least 1024 MiB, including native-memory headroom."
  }
}

variable "api_image" {
  description = <<-EOT
    Fully qualified payments-api image reference. Deployments pin the commit SHA rather than the
    semantic version, because a SHA is unambiguous and a version tag can be moved.
  EOT
  type        = string

  validation {
    condition     = can(regex("^.+(:[a-f0-9]{40}|@sha256:[a-f0-9]{64})$", var.api_image))
    error_message = "api_image must pin a full 40-character commit tag in immutable ECR or a sha256 digest, never a mutable version/latest tag."
  }
}

variable "kafka_image" {
  description = "Kafka broker image, digest-pinned to match the compose stack."
  type        = string
  default     = "apache/kafka:3.9.1@sha256:4ceccc577f03f51f6af8dbfda55194d0d892f4fa7913ffbded567ce3895622ed"

  validation {
    condition     = can(regex("@sha256:[a-f0-9]{64}$", var.kafka_image))
    error_message = "kafka_image must be pinned by digest."
  }
}

# --------------------------------------------------------------------------------------------
# Data stores
# --------------------------------------------------------------------------------------------

variable "database_instance_class" {
  description = "RDS instance class. Matches the postgres:16 the suites already prove against."
  type        = string
  default     = "db.t4g.micro"
}

variable "database_allocated_storage" {
  description = "RDS allocated storage in GB."
  type        = number
  default     = 20
}

variable "database_backup_retention_days" {
  description = <<-EOT
    Automated backup retention. Chapter 65 rehearses a restore from one of these; a retention of
    zero disables backups entirely and would make that rehearsal impossible.
  EOT
  type        = number
  default     = 7

  validation {
    condition     = var.database_backup_retention_days >= 1
    error_message = "database_backup_retention_days must be at least 1; a restore cannot be rehearsed from backups that do not exist."
  }
}

variable "cache_node_type" {
  description = "ElastiCache node type. Redis is ephemeral risk coordination per ADR-0010."
  type        = string
  default     = "cache.t4g.micro"
}

variable "log_retention_days" {
  description = "CloudWatch log retention. Logs are a cost as well as a signal."
  type        = number
  default     = 30
}

variable "monthly_budget_usd" {
  description = <<-EOT
    Total monthly spend that triggers the budget alarm. The Section 7 model estimates ~$134 at
    two API tasks, so the default leaves headroom without hiding a runaway.
  EOT
  type        = number
  default     = 200
}

variable "operations_alert_email" {
  description = "Address that receives budget and CloudWatch alarm notifications."
  type        = string
}
