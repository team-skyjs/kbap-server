locals {
  api_alb_enabled = var.api_alb_name != null && var.api_public_hostname != null
}

data "aws_lb" "api" {
  count = local.api_alb_enabled ? 1 : 0
  name  = var.api_alb_name
}

data "aws_lb_listener" "api_https" {
  count = local.api_alb_enabled ? 1 : 0

  load_balancer_arn = data.aws_lb.api[0].arn
  port              = 443
}

data "aws_route53_zone" "api" {
  count = local.api_alb_enabled ? 1 : 0

  name         = var.route53_zone_name
  private_zone = false
}

resource "aws_lb_target_group" "api" {
  count = local.api_alb_enabled ? 1 : 0

  name                 = substr("${var.cluster_name}-api", 0, 32)
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = data.aws_lb.api[0].vpc_id
  deregistration_delay = 30

  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name = "${var.cluster_name}-api"
  }
}

resource "aws_lb_listener_rule" "api" {
  count = local.api_alb_enabled ? 1 : 0

  listener_arn = data.aws_lb_listener.api_https[0].arn
  priority     = var.api_alb_listener_rule_priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api[0].arn
  }

  condition {
    host_header {
      values = [var.api_public_hostname]
    }
  }
}

resource "aws_route53_record" "api" {
  count = local.api_alb_enabled ? 1 : 0

  zone_id = data.aws_route53_zone.api[0].zone_id
  name    = var.api_public_hostname
  type    = "A"

  alias {
    name                   = data.aws_lb.api[0].dns_name
    zone_id                = data.aws_lb.api[0].zone_id
    evaluate_target_health = true
  }
}

resource "aws_security_group_rule" "alb_to_eks_api" {
  count = local.api_alb_enabled ? 1 : 0

  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  description              = "Existing ALB to EKS API pods 8080"
  security_group_id        = module.eks_environment.node_security_group_id
  source_security_group_id = one(data.aws_lb.api[0].security_groups)
}
