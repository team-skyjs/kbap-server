resource "aws_lb" "this" {
  name               = "${local.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = data.aws_subnets.public.ids
  idle_timeout       = 60
  tags               = local.common_tags
}

# 블루/그린 타깃그룹 한 쌍 — CodeDeploy 가 리스너의 포워딩 대상을 둘 사이에서 바꾼다
resource "aws_lb_target_group" "api" {
  for_each = toset(["blue", "green"])

  name        = "${local.name_prefix}-api-${each.key}"
  vpc_id      = data.aws_vpc.this.id
  port        = 8080
  protocol    = "HTTP"
  target_type = "instance"

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30
  tags                 = merge(local.common_tags, { Color = each.key })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = data.aws_acm_certificate.this.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api["blue"].arn
  }

  # 배포마다 CodeDeploy 가 blue↔green 을 바꾸므로 Terraform 은 초기값만 소유한다
  lifecycle {
    ignore_changes = [default_action]
  }
}

# DNS 는 Terraform 이 소유하지 않는다 — blue/green cutover 는 사람이 판단해 스왑한다(iac/scripts 참고).
# 레코드는 Route53 에서 직접 관리: <env>.kbap.site 가 이 ALB 를 가리키도록 UPSERT.
# (aws_route53_record 를 두면 apply 때마다 <subdomain>.kbap.site 로 되돌려 cutover 를 되돌린다)

# 관리 경로 공개 차단(KB-390). 거부 규칙이지 허용 목록이 아니다 — CodeDeploy 가 blue/green 전환 때 위 리스너의 기본 액션만
# 바꾸므로 "허용 경로 → forward" 규칙을 두면 전환 뒤에도 blue 를 가리켜 카나리가 깨진다. fixed-response 는 forward 가 없어 전환과 무관.
# ALB 헬스체크는 리스너 규칙을 거치지 않고, Alloy 는 호스트 docker 네트워크로 긁으므로 둘 다 영향 없다.
# 퍼센트 인코딩 우회(/%61ctuator)는 dev 실측으로 판정 — 새면 WAF 승격(specs/kb-390 research R-6).
resource "aws_lb_listener_rule" "block_paths" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = var.blocked_path_patterns
    }
  }

  tags = local.common_tags
}
