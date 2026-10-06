# Image registries for the two deployables.
#
# The release workflow pushes each image under both its semantic version and its commit SHA;
# deployments reference the SHA because it is unambiguous, humans reference the version because it
# is memorable. `scripts/release-identity-check.sh` proves both names resolve to one image.

resource "aws_ecr_repository" "payments_api" {
  name                 = "thaap-payments-api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_repository" "analyst_dashboard_ui" {
  name                 = "thaap-analyst-dashboard-ui"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# IMMUTABLE above is the load-bearing setting. A moved tag makes "what is running" unanswerable
# after the fact, and a rollback that pins a version tag someone has since repointed rolls
# forward instead — silently, at the worst possible moment.

# Untagged layers accumulate from every rebuild and are pure cost. Tagged images are kept, because
# a rollback target that expired is not a rollback target.
resource "aws_ecr_lifecycle_policy" "payments_api" {
  repository = aws_ecr_repository.payments_api.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "analyst_dashboard_ui" {
  repository = aws_ecr_repository.analyst_dashboard_ui.name
  policy     = local.ecr_lifecycle_policy
}

locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged layers after 14 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 14
      }
      action = { type = "expire" }
    }]
  })
}
