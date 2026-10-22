# Day 15: Authenticated leader portal

## Sign-in and session handling

The leader-only interface is `web/leader.html`. It uses the Cognito hosted UI
authorization-code flow with PKCE (`S256`) and requests the API's
`saved2serve/leader.read` and `saved2serve/leader.write` custom scopes. The
browser generates a random OAuth `state` and PKCE verifier, keeps them only in
`sessionStorage` for up to five minutes, validates state and the exact callback
origin/path, and removes callback parameters from the address bar before code
exchange.

The Cognito app client must be a public SPA client with no client secret,
authorization code grant enabled, and the above scopes allowed. Do not enable
public self-registration or allow end users to assign themselves to the
`Leaders` group. Group assignment is an administrative operation. API Gateway
validates the access token; `LeaderAuthorization` additionally requires the
verified `Leaders` group for each protected request.

The access token is held in JavaScript memory only. It is not written to
`localStorage`, `sessionStorage`, cookies, URLs, or logs. Reloading the portal
discards the in-memory token; the leader can sign in again through the Cognito
hosted UI. The app does not store refresh tokens. Sign-out clears displayed
submission data and redirects through Cognito's logout endpoint. The portal
also clears its session and private queue/detail view when the access token
expires, including when a backgrounded tab becomes active again.

## Configuration

Set these values in `web/config.js` for the deployed environment:

| Setting | Value |
| --- | --- |
| `apiBaseUrl` | HTTPS API Gateway base URL. |
| `cognito.domain` | HTTPS Cognito managed-login/hosted-UI domain origin. |
| `cognito.clientId` | Public app client ID; not a client secret. |
| `cognito.redirectUri` | Exact HTTPS callback URL for `leader.html`. |
| `cognito.logoutRedirectUri` | Exact allowed logout URL, normally the callback URL. |
| `cognito.scopes` | Both `saved2serve/leader.read` and `saved2serve/leader.write`. |

Register the exact callback and sign-out URLs in the Cognito app client. The
API Gateway HTTP API must use its Cognito JWT authorizer on
`GET /v1/admin/engagements` with `saved2serve/leader.read` and
`PATCH /v1/admin/engagements/{id}` with `saved2serve/leader.write`. Keep the
server-side `Leaders` group check and restrict direct Lambda invocation as
described in [day-10-cognito-jwt-authorizer.md](./day-10-cognito-jwt-authorizer.md).
Configure CORS for the exact portal origin and `GET`/`PATCH` methods, including
`Authorization`, `Accept`, and `Content-Type` headers.
Set a restrictive content-security policy for the static site that allows
scripts/styles only from the site itself and `connect-src` only to the configured
API origin and Cognito domain. Keep referrer policy at `no-referrer` and avoid
hosting/access logs that record OAuth callback query strings, which briefly
contain a one-time authorization code.

## Queue and review

The portal fetches the status-filtered queue with bounded pages and the
server-issued opaque cursor. Selecting a queue item opens an in-page detail
view with submitted content and available contact details. Those details are
rendered as text, are not copied into URLs or browser storage, and are removed
from the page on sign-out. Requests use `Cache-Control: no-store` behavior and
do not log tokens or response data.

Only pending items show approve/reject actions. The portal calls the protected
PATCH endpoint with only the requested status. The server remains authoritative
for scope/group authorization and valid state transitions; unauthorized
requests and stale review conflicts show generic messages without response
body details. This interface does not publish or expose submissions publicly.

## Accessibility and verification

The portal has a skip link, semantic landmarks, labeled filters, keyboard
focus styles, live status/error regions, accessible item/detail labels, and
responsive mobile queue/detail layouts. Queue review content is hidden until
authenticated API data is returned.

Run the dependency-free tests with Node.js 18 or newer:

```powershell
npm --prefix web test
```

Tests cover configuration validation, PKCE/state generation and callback
verification, expiry and logout, volatile token handling, protected queue and
review requests, status/cursor parameters, and safe `401`/`403`/`409` error
handling. They do not verify Cognito or API Gateway configuration against a
deployed AWS environment. No AWS resources or live Cognito client were created
or deployed.
