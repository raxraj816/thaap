variable "region" {
  description = "Region the state bucket and lock table live in."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name. Must match the main root's environment."
  type        = string
  default     = "prod"
}
