# Budget Production AWS Cost Report

- 기준일: 2026-07-29
- 리전: `ap-northeast-2`
- 월 환산: 730시간
- 통화/세금: USD, 세전

| 항목 | 월 예상 USD |
|---|---:|
| EC2 `t3a.large` | 68.33 |
| gp3 110GiB | 10.03 |
| Public IPv4 | 3.65 |
| ACM exportable 단일 FQDN reserve | 2.00 |
| budget platform ECR | 1.00 |
| S3 dump/reference/evidence | 2.50 |
| EBS snapshot | 1.75 |
| CloudWatch/SNS | 2.00 |
| 월간 recovery rehearsal | 0.70 |
| transfer/request buffer | 3.00 |
| **증분 합계** | **94.96** |
| 기존 Route53 zone + state KMS baseline | 1.50~3.50 |
| **계정 총 예상** | **96.46~98.46** |

계산 fixture는 `infra/cost/budget-production-price-fixture.json`, plan gate는
`infra/cost/calculate-budget-production.py`다. 증분 `$95`, 실제 account forecast
`$99`를 넘으면 plan이 실패한다. AWS Budget은 actual `$50`, forecast `$80/$100`,
Cost Anomaly Detection은 일 `$10` 이상을 알린다.

단가 근거:

- [EC2 On-Demand와 Unlimited credit](https://aws.amazon.com/ec2/pricing/on-demand/)
- [EBS 가격](https://aws.amazon.com/ebs/pricing/)
- [Public IPv4 가격](https://aws.amazon.com/vpc/pricing/)
- [ACM exportable certificate 가격](https://aws.amazon.com/certificate-manager/pricing/)
- [S3 가격](https://aws.amazon.com/s3/pricing/)
- [CloudWatch 가격](https://aws.amazon.com/cloudwatch/pricing/)

첫 import/rehearsal의 Unlimited credit은 별도 일회성 evidence다. OpenAI/Kakao
유료 사용, 공공 provider, 도메인 연간비용은 AWS 합계에서 제외하며 provider별
hard budget을 별도로 둔다. `$80` forecast에서는 신규 batch/restore/ML/Admin을
동결하고, `$100` forecast에서도 자동 EC2 stop은 하지 않는다.
