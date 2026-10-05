terraform {
  # Partial configuration. The bucket and table names are supplied at init time rather than
  # committed, because they are account-specific and because this root cannot create the bucket
  # holding its own state — see infra/terraform/bootstrap/.
  #
  #   scripts/terraform.sh init \
  #     -backend-config="bucket=thaap-prod-terraform-state-<suffix>" \
  #     -backend-config="dynamodb_table=thaap-prod-terraform-locks" \
  #     -backend-config="region=ap-south-1" \
  #     -backend-config="key=thaap/prod/terraform.tfstate"
  #
  # Locking is not optional here. Two applies against one state file interleave their writes and
  # produce a state that describes neither result, which is the one failure in this chapter that
  # cannot be fixed by running it again.
  backend "s3" {
    encrypt = true
  }
}
