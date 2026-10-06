# Kafka log storage.
#
# A Fargate task has no durable local disk, so the broker's log directory has to outlive the task
# or every restart loses every partition. EFS is the only network filesystem Fargate mounts
# natively, which makes this less of a choice than a consequence.

resource "aws_efs_file_system" "kafka" {
  creation_token = "${local.name}-kafka"
  encrypted      = true

  # Bursting rather than provisioned: a single broker at this volume never approaches the
  # baseline, and provisioned throughput is billed whether or not it is used.
  throughput_mode = "bursting"

  tags = { Name = "${local.name}-kafka" }
}

resource "aws_efs_mount_target" "kafka" {
  count = var.availability_zone_count

  file_system_id  = aws_efs_file_system.kafka.id
  subnet_id       = aws_subnet.public[count.index].id
  security_groups = [aws_security_group.efs.id]
}

# An access point rather than a raw mount: it pins the ownership and the subdirectory, so the
# broker cannot be handed a filesystem root it then populates inconsistently across restarts.
resource "aws_efs_access_point" "kafka" {
  file_system_id = aws_efs_file_system.kafka.id

  posix_user {
    uid = 1000
    gid = 1000
  }

  root_directory {
    path = "/kafka"

    creation_info {
      owner_uid   = 1000
      owner_gid   = 1000
      permissions = "0755"
    }
  }

  tags = { Name = "${local.name}-kafka" }
}
