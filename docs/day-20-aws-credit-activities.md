# Day 20: Optional AWS credit activities

These are optional learning activities, not prerequisites for the core
Saved2Serve application. Start each activity from the **Explore AWS**
widget in the AWS Console so AWS can record its completion and display the
account-specific reward/status. The reward and eligibility shown there are
authoritative. AWS credits can be consumed by eligible usage; they do not
make resources cost-free or prevent usage from depleting the balance.

Do not deploy or create AWS resources just by reading this document. Keep
these learning resources separate from the application stack and use
synthetic data only.

## Activities and project relationship

| Explore AWS activity | Relationship to this project | Completion and cleanup |
| --- | --- | --- |
| Launch an instance using EC2 | Isolated learning instance only; it must not host or receive application traffic. | Use the activity in Explore AWS, then terminate the instance and check for leftover EBS volumes, snapshots, and Elastic IPs. |
| Use a foundation model in the Amazon Bedrock playground | The project has an optional, leader-only generic draft helper. The Playground activity is separate and must be started from Explore AWS. | Use only a generic topic with no names, contact details, submissions, or other personal data. Stop after the activity and review model usage/costs. |
| Set up a cost budget using AWS Budgets | Already documented in [Day 19 cost controls](./day-19-cost-controls.md) and implemented by `infra/cost-controls.yaml`. | Complete the activity from Explore AWS if it is listed there; verify the activity status and the budget in Billing and Cost Management. A budget sends alerts; it does not cap or stop spending. |
| Create an Aurora or RDS database | Isolated RDS learning database only; it is not connected to the app. DynamoDB remains the application's source of truth. | Use synthetic/sample data only. Delete the database after the activity, decline an unneeded final snapshot, and check for retained snapshots/backups. |

## EC2: isolated learning instance

1. In Console Home, open **Explore AWS** and start **Launch an instance using
   EC2**. Follow the activity's current steps; account eligibility and
   available instance types can vary.
2. Select a smallest eligible general-purpose instance and a standard
   supported Linux AMI. Do not install the application, add real data, attach
   extra storage, allocate an Elastic IP, or configure the instance as a
   public web server.
3. Keep inbound network access closed. Do not create a broad SSH/RDP rule or
   use the instance for production workloads.
4. Once the activity reports completion, terminate the instance unless it is
   still needed for a short lesson. Verify in EC2 that no instance is running
   and remove any activity-created volumes, snapshots, or Elastic IPs that
   are not needed.

## Bedrock: Playground and optional leader helper

### Complete the credit activity

1. Start **Use a foundation model in the Amazon Bedrock playground** from
   **Explore AWS**.
2. Choose a model available to the account in the selected region and use a
   generic prompt such as asking for a short welcome note. Do not include
   names, emails, phone numbers, private engagement content, credentials, or
   other personal or confidential data.
3. Confirm completion and inspect the Bedrock usage/cost view. Model access,
   regional availability, and pricing depend on the account and model.

The Playground activity is the credit-earning task. Using the application's
draft endpoint is optional and is not guaranteed to mark the Playground task
complete.

### Enable the application draft helper (optional)

The leader portal can request a brief draft for one of four fixed generic
topics. It does not send engagement records, free-form prompts, or personal
data to Bedrock. The output is a draft only; a leader must review and edit it,
and the application does not publish it.

The helper is disabled by default. To opt in:

1. Confirm Bedrock model access and the exact foundation model ID in the
   application's deployment region. The default template model is
   `amazon.nova-micro-v1:0`; change `BedrockModelId` if that model is
   unavailable or not enabled for the account.
2. Review the infrastructure change set and deploy the SAM template with
   `EnableBedrockDrafts=true` and the selected `BedrockModelId`. This creates
   a separate Lambda function with `bedrock:InvokeModel` permission limited
   to that foundation model.
3. In `web/config.js`, set `bedrockDraftsEnabled` to `true` only after the
   protected API route has been deployed. Deploy the updated static site.
4. Sign in as an authorized leader and try a fixed topic. Review generated
   content before using it. Check Bedrock costs after testing.
5. To disable the helper, set `bedrockDraftsEnabled` to `false` and deploy
   the static site. Then update the stack with `EnableBedrockDrafts=false` to
   remove the helper function and route.

The helper requires the Cognito `saved2serve/leader.write` scope and the
`Leaders` group. Its input accepts only a fixed topic identifier, and its
logs do not contain the request or generated content. This does not replace
human review, model access controls, or cost monitoring.

## RDS: isolated learning database

1. Start **Create an Aurora or RDS database** from **Explore AWS** and follow
   the activity's current console workflow.
2. Choose a small eligible single-instance RDS configuration if the activity
   permits it. Avoid Multi-AZ, replicas, public access, extra storage, and
   production data. Use only synthetic/sample records.
3. Do not configure the application to use this database. The production
   application remains on its existing DynamoDB data model; this RDS activity
   is an independent learning resource, not a second source of truth.
4. After AWS records completion, delete the DB instance/cluster. Do not keep
   a final snapshot unless deliberately needed; separately inspect snapshots
   and automated backups for retained resources and charges.
5. Verify the RDS resource no longer appears as available/running and inspect
   Billing for any remaining storage or backup usage.

## Cost and completion checks

- Check credit balance, expiry, Free account plan status, and activity status
  in **Billing and Cost Management** and **Explore AWS**.
- Budgets send delayed threshold alerts and do not enforce a spending limit.
  Keep the existing account budget enabled and review actual usage after
  creating any learning resource.
- Credits can expire or be exhausted. Follow the account's Free Tier and
  credit terms; never assume a service or model invocation is free.
- No EC2 instance, Bedrock model request, RDS database, or other AWS resource
  is created by local builds or tests. Deployment and activity completion
  remain explicit operator actions.
