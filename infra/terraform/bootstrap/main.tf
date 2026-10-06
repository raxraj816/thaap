# The state backend, in its own root with local state.
#
# This exists because of a genuine chicken-and-egg: the main root keeps its state in an S3 bucket,
# and something has to create that bucket first. Doing it by hand in the console would make the
# one resource the whole stack depends on the one resource Terraform does not own — and this
# chapter's frozen promise is that the entire topology is rebuildable from zero.
#
# So it is code, applied once, with its own small state file committed nowhere:
#
#   scripts/terraform.sh init      THAAP_TERRAFORM_DIR=infra/terraform/bootstrap
#   scripts/terraform.sh apply     THAAP_TERRAFORM_DIR=infra/terraform/bootstrap
#
# The bucket and table names it prints are then passed to the main root's `init -backend-config`.
# This root's own state is local and disposable: if it is lost, the bucket still exists and can be
# imported, which is not true in the other direction.

terraform {
  required_version = "~> 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "thaap"
      ManagedBy = "terraform"
      Purpose   = "terraform-state"
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name = "thaap-${var.environment}"
  # S3 bucket names are globally unique across every AWS account, so the account ID is the
  # simplest suffix that cannot collide with a stranger's bucket.
  bucket_name = "${local.name}-terraform-state-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  # Losing this bucket means losing the record of what exists. Deleting it should take a
  # deliberate second step.
  lifecycle {
    prevent_destroy = true
  }
}

# Versioning is the recovery path for the failure mode that actually happens: a bad apply writes a
# state that no longer describes reality, and the previous version is the only way back.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# State contains resource identifiers, endpoints and generated passwords. It is never public.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Two concurrent applies against one state file interleave their writes and produce a state that
# describes neither result. It is the one failure in this chapter that running it again does not
# fix, which is why locking is not optional.
resource "aws_dynamodb_table" "locks" {
  name         = "${local.name}-terraform-locks"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  lifecycle {
    prevent_destroy = true
  }
}
