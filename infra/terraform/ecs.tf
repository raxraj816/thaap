# ECS Fargate: the payments-api service and the single Kafka broker.
#
# Two API tasks are safe without leader election. Flyway holds a migration lock, and all eight
# @Scheduled worker classes coordinate through database leases with fencing epochs — proven by the
# Phase 5 restart suites and by ApplicationRestartHarness, which starts and stops real application
# instances against one database. C64-CON-001 re-proves it across two ECS tasks rather than two
# local processes.

data "aws_caller_identity" "current" {}

resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# Service discovery for the broker. The API reaches it at kafka.<env>.thaap.local:9092, which is
# stable across task replacement — a Kafka client connects to whatever the broker advertises, so
# that advertised name has to resolve inside the VPC and has to keep resolving after a restart.
resource "aws_service_discovery_private_dns_namespace" "main" {
  name        = "${var.environment}.thaap.local"
  description = "Internal service discovery"
  vpc         = aws_vpc.main.id
}

resource "aws_service_discovery_service" "kafka" {
  name = "kafka"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# --------------------------------------------------------------------------------------------
# IAM
# --------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# The execution role is used by the ECS agent to pull the image and resolve secrets before the
# container starts. It is deliberately separate from the task role: the application itself has no
# business holding permission to read every secret in the account.
resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = ["secretsmanager:GetSecretValue"]

    # Enumerated rather than wildcarded to the account: this role can read the secrets THAAP
    # needs and no others, so a compromised task definition cannot be edited into an exfiltration
    # tool by pointing it at a different secret.
    resources = concat(
      [for secret in aws_secretsmanager_secret.runtime : secret.arn],
      [aws_secretsmanager_secret.database_password.arn],
    )
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${local.name}-execution-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

# The role the application itself runs as. It needs nothing: THAAP talks to PostgreSQL, Redis and
# Kafka over the network with credentials injected at start, and calls no AWS API. An empty role
# is the correct amount of authority, and is recorded here so its emptiness reads as deliberate.
resource "aws_iam_role" "task" {
  name               = "${local.name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

# --------------------------------------------------------------------------------------------
# payments-api
# --------------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "payments-api"
      image     = var.api_image
      essential = true

      portMappings = [{ containerPort = 8080, protocol = "tcp" }]

      environment = [
        # SPRING_PROFILES_ACTIVE=aws is what turns on RuntimeSecretContractGate. Without it the
        # eight secrets below default to empty strings and the application starts, reports
        # healthy, and protects nothing.
        { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
        { name = "THAAP_POSTGRES_HOST", value = aws_db_instance.main.address },
        { name = "THAAP_POSTGRES_PORT", value = tostring(aws_db_instance.main.port) },
        { name = "THAAP_POSTGRES_DB", value = "thaap_payments" },
        { name = "THAAP_POSTGRES_USER", value = "thaap" },
        { name = "THAAP_REDIS_HOST", value = aws_elasticache_cluster.main.cache_nodes[0].address },
        { name = "THAAP_REDIS_PORT", value = "6379" },
        {
          name  = "THAAP_KAFKA_BOOTSTRAP_SERVERS"
          value = "kafka.${aws_service_discovery_private_dns_namespace.main.name}:9092"
        },
      ]

      # Resolved by the ECS agent before the container starts, so no value is ever present in a
      # task definition, a plan output or a state file.
      secrets = concat(
        [
          for variable, secret in aws_secretsmanager_secret.runtime : {
            name      = variable
            valueFrom = secret.arn
          }
        ],
        [{
          name      = "THAAP_POSTGRES_PASSWORD"
          valueFrom = aws_secretsmanager_secret.database_password.arn
        }],
      )

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.api.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "payments-api"
        }
      }

      # The container's own healthcheck, distinct from the target group's. This one decides
      # whether ECS replaces the task; the target group decides whether the ALB sends it traffic.
      healthCheck = {
        command     = ["CMD-SHELL", "curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness || exit 1"]
        interval    = 30
        timeout     = 5
        retries     = 4
        startPeriod = 120
      }
    }
  ])
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "payments-api"
    container_port   = 8080
  }

  # A rolling deployment briefly runs both versions against one database, which the domain event
  # producer gate and the fencing epochs are designed for. Circuit breaker with rollback means a
  # task definition that cannot reach readiness reverts itself rather than draining the service.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Long enough for the JVM to start, migrate on first boot, and answer readiness before ECS
  # counts a failure against it.
  health_check_grace_period_seconds = 180

  depends_on = [aws_lb_listener_rule.cloudfront_only, aws_iam_role_policy.execution_secrets, aws_iam_role_policy_attachment.execution_managed]
}

# --------------------------------------------------------------------------------------------
# Kafka — one KRaft broker
# --------------------------------------------------------------------------------------------
#
# Not highly available, recorded honestly as such in Section 13, with a named replacement
# trigger: MSK when a real merchant pilot begins, or earlier if a broker restart is observed to
# lose an event the outbox cannot replay. MSK Serverless has a ~$550/month floor against this
# broker's ~$14, and disabling events is not an option — Phase 5 and 6 analytics, projections and
# workspace insights all read the stream.

resource "aws_ecs_task_definition" "kafka" {
  family                   = "${local.name}-kafka"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.kafka_cpu
  memory                   = var.kafka_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  volume {
    name = "kafka-data"

    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.kafka.id
      transit_encryption = "ENABLED"

      authorization_config {
        access_point_id = aws_efs_access_point.kafka.id
        iam             = "DISABLED"
      }
    }
  }

  container_definitions = jsonencode([
    {
      name      = "kafka"
      image     = var.kafka_image
      essential = true

      portMappings = [{ containerPort = 9092, protocol = "tcp" }]

      mountPoints = [{
        sourceVolume  = "kafka-data"
        containerPath = "/var/lib/kafka/data"
        readOnly      = false
      }]

      environment = [
        { name = "KAFKA_NODE_ID", value = "1" },
        { name = "KAFKA_LOG_DIRS", value = "/var/lib/kafka/data" },
        # Leave native buffers, metaspace and thread stacks room in the 1 GiB task.
        { name = "KAFKA_HEAP_OPTS", value = "-Xms512m -Xmx512m" },
        { name = "KAFKA_PROCESS_ROLES", value = "broker,controller" },
        { name = "KAFKA_LISTENERS", value = "PLAINTEXT://:9092,CONTROLLER://:9093" },
        # A client connects to what the broker advertises, not to what it was handed. This must
        # be the service-discovery name or every producer resolves an address it cannot reach —
        # the defect Chapter 60 found when the compose stack advertised only localhost.
        {
          name  = "KAFKA_ADVERTISED_LISTENERS"
          value = "PLAINTEXT://kafka.${aws_service_discovery_private_dns_namespace.main.name}:9092"
        },
        { name = "KAFKA_INTER_BROKER_LISTENER_NAME", value = "PLAINTEXT" },
        { name = "KAFKA_CONTROLLER_LISTENER_NAMES", value = "CONTROLLER" },
        {
          name  = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP"
          value = "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
        },
        { name = "KAFKA_CONTROLLER_QUORUM_VOTERS", value = "1@127.0.0.1:9093" },
        { name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", value = "1" },
        { name = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", value = "1" },
        { name = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", value = "1" },
        { name = "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", value = "0" },
        { name = "KAFKA_AUTO_CREATE_TOPICS_ENABLE", value = "false" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.kafka.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "kafka"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "kafka" {
  name            = "${local.name}-kafka"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.kafka.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.kafka.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.kafka.arn
  }

  # One broker with EFS-backed storage: two tasks would both claim the same log directory and
  # corrupt it, so the ceiling is the floor.
  deployment_maximum_percent         = 100
  deployment_minimum_healthy_percent = 0

  depends_on = [aws_efs_mount_target.kafka, aws_iam_role_policy_attachment.execution_managed]
}
