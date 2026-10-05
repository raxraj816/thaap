# Provider mocks exercise Terraform evaluation and boundary configuration, never AWS behavior.
mock_provider "aws" {
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:ap-south-1:123456789012:certificate/00000000-0000-0000-0000-000000000001"
      domain_validation_options = [{
        domain_name           = "workspace.example.test"
        resource_record_name  = "_token.workspace.example.test"
        resource_record_type  = "CNAME"
        resource_record_value = "_token.acm-validations.aws"
      }]
    }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["zone-a", "zone-b", "zone-c", "zone-d"] }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}
mock_provider "aws" {
  alias = "cloudfront"
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000002"
      domain_validation_options = [{
        domain_name           = "workspace.example.test"
        resource_record_name  = "_token.workspace.example.test"
        resource_record_type  = "CNAME"
        resource_record_value = "_token.acm-validations.aws"
      }]
    }
  }
}
mock_provider "random" {}

variables {
  domain_name              = "example.test"
  workspace_hostname       = "workspace.example.test"
  hosted_zone_id           = "Z1234567890123"
  operator_allowlist_cidrs = ["192.0.2.1/32"]
  operations_alert_email   = "operator@example.test"
  api_image                = "123456789012.dkr.ecr.ap-south-1.amazonaws.com/thaap-payments-api:0123456789abcdef0123456789abcdef01234567"
}

run "foundation_plan" {
  command = plan
  assert {
    condition     = aws_ecs_service.api.desired_count == 0
    error_message = "The first apply must create secret entries without starting unconfigured API tasks."
  }
  assert {
    condition     = one([for r in aws_wafv2_web_acl.main.rule : r.priority if r.name == "operator-rate-limit"]) < one([for r in aws_wafv2_web_acl.main.rule : r.priority if r.name == "operator-allowlist"])
    error_message = "A terminating allow must not short-circuit the rate block."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.main.custom_error_response) == 0
    error_message = "Global error rewrites must never turn API errors into SPA success."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.main.ordered_cache_behavior) == 41 && alltrue([for b in aws_cloudfront_distribution.main.ordered_cache_behavior : b.target_origin_id == "spa" || length(b.function_association) == 1])
    error_message = "All 20 API/operational roots need exact and nested behaviors plus the trusted-hop function."
  }
  assert {
    condition     = contains([for e in jsondecode(aws_ecs_task_definition.kafka.container_definitions)[0].environment : "${e.name}=${e.value}"], "KAFKA_LOG_DIRS=/var/lib/kafka/data")
    error_message = "Kafka must write to its mounted persistent directory."
  }
  assert {
    condition     = aws_cloudwatch_metric_alarm.api_5xx.metric_name == "HTTPCode_Target_5XX_Count"
    error_message = "An ALB-only metric does not observe application server errors."
  }
  assert {
    condition     = contains([for e in jsondecode(aws_ecs_task_definition.kafka.container_definitions)[0].environment : "${e.name}=${e.value}"], "KAFKA_HEAP_OPTS=-Xms512m -Xmx512m") && aws_ecs_task_definition.kafka.memory >= 1024
    error_message = "Broker heap must leave native-memory headroom in the task budget."
  }
}

run "two_task_plan" {
  command = plan
  variables { api_desired_count = 2 }
  assert {
    condition     = aws_ecs_service.api.desired_count == 2 && one(aws_ecs_task_definition.api.runtime_platform).cpu_architecture == "ARM64"
    error_message = "The activated topology must run two ARM64 API tasks."
  }
}

run "insufficient_broker_memory" {
  command = plan
  variables { kafka_memory = 512 }
  expect_failures = [var.kafka_memory]
}

run "second_region_plan" {
  command = plan
  variables { region = "eu-west-1" }
  assert {
    condition     = aws_s3_bucket.spa.bucket == "thaap-prod-workspace-eu-west-1-123456789012"
    error_message = "Deployment configuration must follow the selected region."
  }
}

run "empty_allowlist" {
  command = plan
  variables { operator_allowlist_cidrs = [] }
  expect_failures = [var.operator_allowlist_cidrs]
}
run "world_allowlist" {
  command = plan
  variables { operator_allowlist_cidrs = ["0.0.0.0/0"] }
  expect_failures = [var.operator_allowlist_cidrs]
}
run "noncanonical_world_allowlist" {
  command = plan
  variables { operator_allowlist_cidrs = ["192.0.2.1/0"] }
  expect_failures = [var.operator_allowlist_cidrs]
}
run "malformed_allowlist" {
  command = plan
  variables { operator_allowlist_cidrs = ["not-a-cidr"] }
  expect_failures = [var.operator_allowlist_cidrs]
}
run "ipv6_in_ipv4_set" {
  command = plan
  variables { operator_allowlist_cidrs = ["2001:db8::1/128"] }
  expect_failures = [var.operator_allowlist_cidrs]
}
run "fractional_zones" {
  command = plan
  variables { availability_zone_count = 2.5 }
  expect_failures = [var.availability_zone_count]
}
run "wrong_control_plane_region" {
  command = plan
  variables { cloudfront_control_plane_region = "eu-west-1" }
  expect_failures = [var.cloudfront_control_plane_region]
}
run "negative_tasks" {
  command = plan
  variables { api_desired_count = -1 }
  expect_failures = [var.api_desired_count]
}
run "fractional_tasks" {
  command = plan
  variables { api_desired_count = 1.5 }
  expect_failures = [var.api_desired_count]
}
run "mutable_api_image" {
  command = plan
  variables { api_image = "example.test/thaap-payments-api:latest" }
  expect_failures = [var.api_image]
}
run "mutable_broker_image" {
  command = plan
  variables { kafka_image = "apache/kafka:3.9.1" }
  expect_failures = [var.kafka_image]
}

run "certificate_dns_resolution" {
  # Mocked apply resolves computed data, with identical regional DNS tokens. No AWS calls.
  command = apply
  plan_options {
    # Limit this mock resolution test to ACM/DNS; unrelated fake ARN values are not AWS IDs.
    target = [aws_acm_certificate_validation.alb, aws_acm_certificate_validation.cloudfront]
  }
  assert {
    condition     = aws_route53_record.certificate_validation.name == "_token.workspace.example.test" && aws_acm_certificate_validation.alb.validation_record_fqdns == aws_acm_certificate_validation.cloudfront.validation_record_fqdns
    error_message = "Both regional certificates must share one DNS validation resource without duplicate keys."
  }
}
