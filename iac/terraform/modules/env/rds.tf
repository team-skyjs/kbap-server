resource "aws_db_subnet_group" "this" {
  name       = "kbap-${var.env}-db-subnets"
  subnet_ids = data.aws_subnets.private.ids
}

resource "aws_db_instance" "mysql" {
  identifier     = "kbap-${var.env}-mysql"
  engine         = "mysql"
  engine_version = "8.0"

  instance_class    = var.db_instance_class
  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = "kbap"
  username = var.db_username

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  multi_az               = false
  publicly_accessible    = false

  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false

  apply_immediately = true
}
