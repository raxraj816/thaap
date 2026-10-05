terraform {
  required_version = "~> 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

# CloudFront's control plane lives in one region, and two resources genuinely require it:
# a certificate attached to a distribution, and a WAF WebACL scoped to CLOUDFRONT. This alias is
# not a second deployment region — it is an AWS API constraint — which is why it comes from a
# variable with a fixed default rather than from var.region.
provider "aws" {
  alias  = "cloudfront"
  region = var.cloudfront_control_plane_region

  default_tags {
    tags = local.tags
  }
}
