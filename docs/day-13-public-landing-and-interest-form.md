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
