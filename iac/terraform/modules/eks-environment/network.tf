resource "aws_subnet" "eks_public" {
  for_each = {
    a = {
      az   = "ap-northeast-2a"
      cidr = var.eks_public_subnet_a_cidr
    }
    b = {
      az   = "ap-northeast-2b"
      cidr = var.eks_public_subnet_b_cidr
    }
  }

  vpc_id                  = data.aws_vpc.this.id
  cidr_block              = each.value.cidr
  availability_zone       = each.value.az
  map_public_ip_on_launch = true

  tags = {
    Name                                        = "kbap-${var.cluster_name}-${each.key}-eks-public"
    "kubernetes.io/role/elb"                    = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

resource "aws_route_table" "eks_public" {
  vpc_id = data.aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = data.aws_internet_gateway.this.id
  }

  tags = {
    Name = "kbap-${var.cluster_name}-public-eks-rt"
  }
}

resource "aws_route_table_association" "eks_public" {
  for_each       = aws_subnet.eks_public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.eks_public.id
}
