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

resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = local.fqdn
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}
