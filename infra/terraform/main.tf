module "dev" {
  source = "./modules/env"

  env                   = "dev"
  vpc_name              = "kbap-devstg-vpc"
  instance_type         = "t3.small"
  asg_min_size          = 1
  asg_max_size          = 2
  service_desired       = 1
  container_cpu         = 512
  container_memory      = 1024
  db_instance_class     = "db.t4g.micro"
  redis_node_type       = "cache.t4g.micro"
  api_image             = "118178010621.dkr.ecr.ap-northeast-2.amazonaws.com/kbap/api:CHANGE_ME_SHA"
  certificate_arn       = null
  db_username           = "kbap"
  storage_bucket        = "CHANGE_ME"
  cdn_base_url          = "CHANGE_ME"
  image_public_base_url = "CHANGE_ME"
}
