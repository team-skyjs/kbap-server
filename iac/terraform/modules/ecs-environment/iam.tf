data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "codedeploy_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["codedeploy.amazonaws.com"]
    }
  }
}

# --- 컨테이너 인스턴스 (EC2) ---
resource "aws_iam_role" "instance" {
  name               = "${local.name_prefix}-container-instance-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "instance_ecs" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
}

resource "aws_iam_role_policy_attachment" "instance_ssm" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "instance" {
  name = "${local.name_prefix}-container-instance-profile"
  role = aws_iam_role.instance.name
}

# --- 태스크 실행 롤 (ECR pull · 로그 · SSM 시크릿 주입) ---
resource "aws_iam_role" "task_execution" {
  name               = "${local.name_prefix}-task-exec-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "task_execution_secrets" {
  statement {
    actions   = ["ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}/*"]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [data.aws_kms_alias.ssm.target_key_arn]
  }
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name   = "ssm-secrets"
  role   = aws_iam_role.task_execution.id
  policy = data.aws_iam_policy_document.task_execution_secrets.json
}

# --- api 태스크 롤 (앱 코드가 쓰는 AWS 권한 — S3) ---
resource "aws_iam_role" "api_task" {
  name               = "${local.name_prefix}-api-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = local.common_tags
}

data "aws_iam_policy_document" "api_task" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::${var.storage_bucket}"]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.storage_key_prefix}/*", "images/*"]
    }
  }
  statement {
    actions = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [
      "arn:aws:s3:::${var.storage_bucket}/${var.storage_key_prefix}/*",
      "arn:aws:s3:::${var.storage_bucket}/images/*",
    ]
  }
}

resource "aws_iam_role_policy" "api_task" {
  name   = "s3-storage"
  role   = aws_iam_role.api_task.id
  policy = data.aws_iam_policy_document.api_task.json
}

# --- batch 태스크 롤 (SQS 발행 + S3 읽기) ---
resource "aws_iam_role" "batch_task" {
  name               = "${local.name_prefix}-batch-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = local.common_tags
}

data "aws_iam_policy_document" "batch_task" {
  statement {
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
    resources = [data.aws_sqs_queue.food_content.arn]
  }
  statement {
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["arn:aws:s3:::${var.storage_bucket}/images/*"]
  }
  # ECS Exec — 컨테이너에 주입되는 SSM 에이전트가 제어/데이터 채널을 아웃바운드로 연다(리소스 한정 불가 액션)
  statement {
    sid = "EcsExecChannel"
    actions = [
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "batch_task" {
  name   = "sqs-s3"
  role   = aws_iam_role.batch_task.id
  policy = data.aws_iam_policy_document.batch_task.json
}

# --- batch 운영 사용자 (원격 잡 실행 — 이 환경 클러스터의 batch 컨테이너에만 ECS Exec) ---
# 액세스 키는 만들지 않는다 — state 에 시크릿을 남기지 않기 위해 콘솔에서 발급해 젠킨스 크리덴셜에만 둔다.
resource "aws_iam_user" "batch_operator" {
  name = "${local.name_prefix}-batch-operator"
  tags = local.common_tags
}

data "aws_iam_policy_document" "batch_operator" {
  statement {
    sid       = "FindBatchTask"
    actions   = ["ecs:ListTasks", "ecs:DescribeTasks"]
    resources = ["*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.this.arn]
    }
  }
  statement {
    sid       = "ExecIntoBatchOnly"
    actions   = ["ecs:ExecuteCommand"]
    resources = ["arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task/${aws_ecs_cluster.this.name}/*"]
    condition {
      test     = "StringEquals"
      variable = "ecs:container-name"
      values   = [local.batch_container_name]
    }
  }
}

resource "aws_iam_user_policy" "batch_operator" {
  name   = "batch-remote-run"
  user   = aws_iam_user.batch_operator.name
  policy = data.aws_iam_policy_document.batch_operator.json
}

# --- CodeDeploy (ECS 블루/그린) ---
resource "aws_iam_role" "codedeploy" {
  name               = "${local.name_prefix}-codedeploy-role"
  assume_role_policy = data.aws_iam_policy_document.codedeploy_assume.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "codedeploy_ecs" {
  role       = aws_iam_role.codedeploy.name
  policy_arn = "arn:aws:iam::aws:policy/AWSCodeDeployRoleForECS"
}
