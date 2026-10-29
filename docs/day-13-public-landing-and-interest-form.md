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
