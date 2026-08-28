module "ecs_environment" {
  source = "./modules/ecs-environment"

  env              = var.env
  region           = var.region
  vpc_name         = var.vpc_name
  subdomain        = var.subdomain
  hosted_zone_name = var.hosted_zone_name

  instance_type        = var.instance_type
  api_instance_count   = var.api_instance_count
  batch_instance_count = var.batch_instance_count
  batch_desired_count  = var.batch_desired_count
  api_desired_count    = var.api_desired_count

  api_image   = var.api_image
  batch_image = var.batch_image

  spring_profile          = var.spring_profile
  db_url                  = var.db_url
  db_username             = var.db_username
  redis_host              = var.redis_host
  rds_security_group_id   = var.rds_security_group_id
  redis_security_group_id = var.redis_security_group_id

  storage_bucket          = var.storage_bucket
  storage_key_prefix      = var.storage_key_prefix
  cdn_base_url            = var.cdn_base_url
  image_public_base_url   = var.image_public_base_url
  food_content_queue_name = var.food_content_queue_name

  log_retention_days            = var.log_retention_days
  canary_percentage             = var.canary_percentage
  canary_interval_minutes       = var.canary_interval_minutes
  blue_termination_wait_minutes = var.blue_termination_wait_minutes

  admin_cidr            = var.admin_cidr
  bastion_instance_type = var.bastion_instance_type
  bastion_key_name      = var.bastion_key_name

  home_prometheus_remote_write_url = var.home_prometheus_remote_write_url
  alloy_image                      = var.alloy_image
}
