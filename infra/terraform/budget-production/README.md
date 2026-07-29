# Budget production Terraform

This root owns the isolated, single-node `budget-production` profile. It does
not share resources or state with staging or the existing HA production root.

The remote backend key is fixed to
`home-search/budget-production/terraform.tfstate`. Supply the values from
`backend.s3.tf.example` during initialization. Terraform workspaces are
forbidden.

Rollout is monotonic and reviewed in this order:

1. `registry`
2. `foundation`
3. `data`
4. `private`
5. `public`

`public_dns_enabled` defaults to `false` even in the public phase. DNS requires
a separate zero-destroy plan after dark smoke and restore evidence pass.

The exact ECS-optimized Amazon Linux 2023 AMI and availability zone are inputs,
not data-source lookups. Foundation evidence must record both before apply.

The data EBS volume, backup/reference buckets, ACM certificate, and SSM
parameter containers use `prevent_destroy`. The EC2 host formats an attached
device only after confirming the expected volume ID, AZ, an empty signature
table, and no existing filesystem. Any ambiguity stops the bootstrap.
