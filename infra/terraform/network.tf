# VPC, subnets and security groups.
#
# There is no NAT Gateway. Tasks run in public subnets with public IPs so they can reach ECR,
# Secrets Manager and CloudWatch, and the security groups below are what make that safe rather
# than merely cheap — this is the third of the three Section 5.1 exposure layers, and the one
# that stops a task being addressable directly.

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name }
}

# Public subnets carry the ALB and the tasks. Private subnets carry RDS and ElastiCache, which
# have no route to the internet at all and are reachable only from the task security group.
resource "aws_subnet" "public" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  availability_zone = data.aws_availability_zones.available.names[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)

  # Required for a Fargate task to reach ECR without a NAT Gateway.
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  availability_zone = data.aws_availability_zones.available.names[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 100)

  tags = { Name = "${local.name}-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# No routes out. A data store that cannot reach the internet cannot exfiltrate to it either.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# --------------------------------------------------------------------------------------------
# Security groups
# --------------------------------------------------------------------------------------------
#
# Every rule below references another security group rather than a CIDR. That is deliberate and
# it is the control: a CIDR rule permitting the VPC range would admit anything that happens to
# land in the VPC, while a group reference admits exactly the thing that holds the group.

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Ingress to the load balancer"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-alb" }
}

# The ALB is reachable from anywhere on 443, and that is not the exposure boundary — CloudFront's
# WAF is (Section 5.1, layer one), and the ALB listener's shared-secret rule is (layer two).
# Restricting this to CloudFront's published prefix list is possible and is deliberately not
# relied on: those ranges change, and a stale prefix list fails closed in a way that looks like
# an application outage. The listener rule is the control that does not decay.
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from CloudFront; the shared-secret listener rule is what authorises"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  description       = "ALB reaches its targets"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "api" {
  name        = "${local.name}-api"
  description = "payments-api tasks"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-api" }
}

# The whole of Section 5.1 layer three: the API port is reachable from the load balancer's
# security group and from nothing else, including other tasks in the same public subnet.
resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "Application port, from the ALB only"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "api_all" {
  security_group_id = aws_security_group.api.id
  description       = "ECR, Secrets Manager, CloudWatch, and the data stores"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "kafka" {
  name        = "${local.name}-kafka"
  description = "Single KRaft broker"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-kafka" }
}

resource "aws_vpc_security_group_ingress_rule" "kafka_from_api" {
  security_group_id            = aws_security_group.kafka.id
  description                  = "Broker port, from the API tasks only"
  ip_protocol                  = "tcp"
  from_port                    = 9092
  to_port                      = 9092
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_vpc_security_group_egress_rule" "kafka_all" {
  security_group_id = aws_security_group.kafka.id
  description       = "Image pull and log shipping"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "PostgreSQL"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-database" }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_api" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL, from the API tasks only"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_security_group" "cache" {
  name        = "${local.name}-cache"
  description = "Redis"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-cache" }
}

resource "aws_vpc_security_group_ingress_rule" "cache_from_api" {
  security_group_id            = aws_security_group.cache.id
  description                  = "Redis, from the API tasks only"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_security_group" "efs" {
  name        = "${local.name}-efs"
  description = "Kafka log storage"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-efs" }
}

resource "aws_vpc_security_group_ingress_rule" "efs_from_kafka" {
  security_group_id            = aws_security_group.efs.id
  description                  = "NFS, from the broker only"
  ip_protocol                  = "tcp"
  from_port                    = 2049
  to_port                      = 2049
  referenced_security_group_id = aws_security_group.kafka.id
}
