# The analyst workspace bundle.
#
# S3 and CloudFront rather than serving static assets from the API container: a static asset path
# inside a payment service's blast radius is a category of bug that has no business existing
# there, and the workspace is a pure SPA with nothing to gain from sharing a process.
#
# The bucket is not public. CloudFront reaches it through an origin access control, and the bucket
# policy admits that distribution and nothing else.

resource "aws_s3_bucket" "spa" {
  bucket = "${local.name}-workspace-${var.region}-${data.aws_caller_identity.current.account_id}"

  tags = { Name = "${local.name}-workspace" }
}

resource "aws_s3_bucket_public_access_block" "spa" {
  bucket = aws_s3_bucket.spa.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "spa" {
  bucket = aws_s3_bucket.spa.id

  # A bad bundle is rolled back by promoting the previous version rather than by rebuilding it,
  # which matters when the reason for the rollback is that the build is broken.
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "spa" {
  statement {
    sid     = "AllowCloudFrontRead"
    actions = ["s3:GetObject"]

    resources = ["${aws_s3_bucket.spa.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # Scoped to this distribution rather than to the service: without this condition any
    # CloudFront distribution in any AWS account could read the bucket.
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "spa" {
  bucket = aws_s3_bucket.spa.id
  policy = data.aws_iam_policy_document.spa.json
}
