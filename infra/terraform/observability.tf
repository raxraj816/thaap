# Log groups, alarms and the budget.
#
# Every alarm here is created by Terraform and none of them is evidence yet. `C65-OBS-002` closes
# only when each has been driven into ALARM deliberately and the notification observed arriving:
# an alarm that has never fired is a configuration, not a control, and one that fires into a
# destination nobody reads is the same outage with more paperwork.

resource "aws_cloudwatch_log_group" "api" {
  name              = "/thaap/${var.environment}/payments-api"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/thaap/${var.environment}/kafka"
  retention_in_days = var.log_retention_days
}

resource "aws_sns_topic" "operations" {
  name = "${local.name}-operations"
}

# The subscription is created pending and stays pending until the address confirms by email. That
# confirmation is a Chapter 65 step and is exactly the sort of thing that gets assumed rather than
# checked, which is why the alarm rehearsal has to observe delivery rather than state.
resource "aws_sns_topic_subscription" "operations_email" {
  topic_arn = aws_sns_topic.operations.arn
  protocol  = "email"
  endpoint  = var.operations_alert_email
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "The load balancer is returning server errors to the workspace."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = { LoadBalancer = aws_lb.main.arn_suffix }

  alarm_actions = [aws_sns_topic.operations.arn]
  ok_actions    = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${local.name}-api-5xx"
  alarm_description   = "API targets returned server errors, even when the ALB itself is healthy."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  dimensions          = { LoadBalancer = aws_lb.main.arn_suffix }
  alarm_actions       = [aws_sns_topic.operations.arn]
  ok_actions          = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "api_task_count" {
  alarm_name          = "${local.name}-api-task-count"
  alarm_description   = "Fewer payments-api tasks are running than the service desires."
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = var.api_desired_count
  comparison_operator = "LessThanThreshold"

  # A missing datapoint here means the metric stopped arriving, which is not reassuring.
  treat_missing_data = var.api_desired_count == 0 ? "notBreaching" : "breaching"

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.api.name
  }

  alarm_actions = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "kafka_task_count" {
  alarm_name          = "${local.name}-kafka-task-count"
  alarm_description   = "The single Kafka broker is not running. Publication stops; the outbox retains."
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"

  dimensions = {
    ClusterName = aws_ecs_cluster.main.name
    ServiceName = aws_ecs_service.kafka.name
  }

  alarm_actions = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_cpu" {
  alarm_name          = "${local.name}-database-cpu"
  alarm_description   = "RDS CPU saturation."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = { DBInstanceIdentifier = aws_db_instance.main.identifier }

  alarm_actions = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${local.name}-database-storage"
  alarm_description   = "RDS free storage is running out."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648 # 2 GiB
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"

  dimensions = { DBInstanceIdentifier = aws_db_instance.main.identifier }

  alarm_actions = [aws_sns_topic.operations.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_connections" {
  alarm_name          = "${local.name}-database-connections"
  alarm_description   = "RDS connection saturation. Two API tasks at ten each should be nowhere near this."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  threshold           = 60
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  dimensions = { DBInstanceIdentifier = aws_db_instance.main.identifier }

  alarm_actions = [aws_sns_topic.operations.arn]
}

# Spend, so it arrives before the invoice does. Section 7 estimates ~$134/month at two API tasks.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Forecast first, so a runaway is caught while it is still cheap rather than at month end.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.operations_alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.operations_alert_email]
  }
}
