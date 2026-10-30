# Day 13: Public landing page and interest form

## Scope and privacy

The static public page lives in `web/`. It introduces the Save2Serve MVP,
explains a simple registration flow, and provides a responsive interest form.
The form collects name, email, and coarse area/postcode; phone and follow-up
email permission are optional. Submission consent is required and separate
from the optional follow-up checkbox, which is unchecked by default.

The page does not ask for a full residential address, precise coordinates, or
geolocation access. The postcode/area is kept as text so leading zeroes are
preserved. No form values are written to browser storage, query strings, or
logs. API calls omit browser credentials and the referrer.

## API connection

Before hosting, set `apiBaseUrl` in `web/config.js` to the HTTPS API base URL.
The page posts an allowlisted JSON body to `POST /v1/interests`, with a
client-generated idempotency key and `source: "landing-page"`. The key stays
in memory and is reused only for retries with the same form values. Successful
responses show a confirmation; validation, duplicate/conflict, throttling,
service, and network failures show non-sensitive, retry-friendly messages.

Configure the API Gateway CORS policy to allow the static site's exact origin,
the `POST` method, and `Content-Type` header. Keep production API and site
traffic on HTTPS. Do not include request bodies, query strings containing
user data, or authorization material in hosting/API access logs. This work
does not provision static hosting, API Gateway CORS, or other AWS resources.

## Local preview

From the repository root, run:

```powershell
python -m http.server 8000 --directory web
```

Open `http://localhost:8000`. For a real API submission, configure the API base
URL and allow the local origin in a non-production CORS environment; the
repository does not configure live API credentials or services.

## Public static demo on EC2

For an AWS-service demonstration, `infra/ec2-user-data.sh` bootstraps an
Amazon Linux 2023 instance with Nginx, downloads the public `main` branch
archive, and serves the contents of `web/` over HTTP. It does not need an SSH
key or shell access to the instance.

1. In the AWS Console, keep **Europe (Stockholm) (`eu-north-1`)** selected, open **EC2**,
   and choose **Launch instance**.
2. Select **Amazon Linux 2023**, a small instance type such as `t3.micro`, and
   a new or existing security group. Choose **Proceed without a key pair**;
   this demo does not use SSH. Enable auto-assignment of a public IPv4 address.
3. Configure the security group to allow inbound **HTTP (TCP 80)** from
   `0.0.0.0/0`. Do not add an SSH rule for this static demo.
4. Under **Advanced details → User data**, paste the complete contents of
   `infra/ec2-user-data.sh`, then launch the instance.
5. Wait a few minutes for first-boot setup. In the EC2 instance details, copy
   its public IPv4 address and open `http://<public-ip>` in a browser.

The instance downloads the `main` branch when it first boots, so changes must
be pushed to GitHub before launching. User data normally runs only on first
boot; to deploy a later revision, relaunch an instance or update its files
through a separate deployment method.

This is an HTTP-only static demo. EC2 compute, its attached storage, public IPv4,
and data transfer can incur charges; review your account's pricing and budget
before launch. Terminate the instance when finished; stopping it halts compute
charges but does not remove attached storage. Do not enter real personal
information. `apiBaseUrl` in `web/config.js` is empty, so
submissions and church/devotion lookups have no backend, and leader sign-in
requires the separate API and Cognito deployment. Use HTTPS and configure the
API's CORS allowlist before collecting real submissions.

### Fictional-data interest-flow demonstration

After connecting to the instance with EC2 Instance Connect, the optional
`infra/install-ec2-demo-interest-api.sh` installer adds a small local Python
endpoint behind Nginx that writes to DynamoDB using the EC2 instance profile.
Before installing it:

1. In `eu-north-1`, create a DynamoDB table, for example
   `Saved2ServeDemoRegistrations`, with partition key `registrationId` (String),
   no sort key, and on-demand capacity.
2. Create an EC2 IAM role and attach this least-privilege inline policy,
   replacing the account ID with your AWS account ID:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["dynamodb:PutItem", "dynamodb:GetItem"],
       "Resource": "arn:aws:dynamodb:eu-north-1:ACCOUNT_ID:table/Saved2ServeDemoRegistrations"
     }]
   }
   ```

   Set the role's trusted entity to **AWS service → EC2**, create the role, then
   attach it to the running instance through **EC2 → Actions → Security →
   Modify IAM role**. Never put access keys in the instance or repository.
3. Push the updated repository to `main`. In EC2 Instance Connect, run:

```bash
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/LindokuhleMPMahlangu/Saved2ServeConnect/main/infra/install-ec2-demo-interest-api.sh \
  --output /tmp/install-ec2-demo-interest-api.sh
sudo bash /tmp/install-ec2-demo-interest-api.sh Saved2ServeDemoRegistrations eu-north-1
```

The public form displays a demo-only warning. The endpoint validates and saves
fictional registrations to the private DynamoDB table, returns a generated
registration ID, and does not log request fields. DynamoDB encrypts stored data
at rest by default. The HTTPS URL provided by a Cloudflare Quick Tunnel is
temporary and is not a production hosting or data-protection setup. Do not
submit real personal information. This is not the production Lambda/DynamoDB
service.

To view saved demo rows, open **DynamoDB → Tables → Saved2ServeDemoRegistrations
→ Explore table items** in the AWS Console.

After the installer succeeds, remove the temporary SSH security-group rule
used for EC2 Instance Connect when you disconnect. To stop the temporary
Cloudflare URL, stop the `cloudflared` process; the EC2 demo remains available
at its HTTP public IP.

If first boot fails, inspect **Actions → Monitor and troubleshoot → Get system
log**. User data is normally a one-time startup script. After correcting a
startup failure, launch a replacement instance with the corrected script and
terminate the failed instance only after confirming the replacement serves the
site.

## Verification

With Node.js 18 or newer, run the dependency-free JavaScript tests with:

```powershell
npm --prefix web test
```

The tests cover required-field validation, optional consent separation,
payload minimization, API request options, and generic API/network failure
handling. The page uses semantic landmarks and labels, a skip link, visible
keyboard focus, associated validation messages, and a polite live region for
submission outcomes. Layout behavior is responsive and respects reduced-motion
preferences.
