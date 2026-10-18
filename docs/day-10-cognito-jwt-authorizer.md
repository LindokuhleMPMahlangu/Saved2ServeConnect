# Day 10 - Cognito JWT authorizer for leader-only routes

## Scope and current state

This repository has no API Gateway or Cognito infrastructure-as-code. The
leader engagement routes described here are implemented by
`LeaderEngagementApiHandler`, but this document defines deployment
configuration only; it does not claim that an AWS account has been configured.

Use an API Gateway **HTTP API JWT authorizer** to validate Cognito access
tokens, with route scopes to separate read and write operations. API Gateway's
JWT authorizer validates token signatures, issuer, audience, expiry, and
configured scopes; it does **not** authorize Cognito group membership. The
integrated Lambda must additionally require the exact `Leaders` value in the
verified `cognito:groups` claim before reading or changing leader-only data.
Both checks must pass.

## Cognito configuration

1. In the user pool, create a resource server with identifier `saved2serve`.
2. Add the custom scopes `leader.read` and `leader.write`. Their full scope
   names are `saved2serve/leader.read` and `saved2serve/leader.write`.
3. Create the `Leaders` user-pool group and grant membership only to approved
   leaders. Do not treat possession of a valid user-pool account as leader
   authorization.
4. Configure the application client to allow the resource server scopes and
   use the authorization-code OAuth flow with PKCE. The client requests only
   the scopes needed for the operation. Send the resulting **access token**,
   not the ID token, as `Authorization: Bearer <access-token>`.
5. Assign the `Leaders` group only through a trusted administrative process.
   Do not allow public sign-up, a client-supplied claim, or a public request to
   grant leader membership. Verify group membership independently of granted
   OAuth scopes.
6. Keep the user-pool ID, app-client ID, and API configuration in the intended
   deployment environment. Do not commit credentials or token values.

Because an enabled custom scope can be requested by any user of an app client,
the Lambda group check is mandatory even when API Gateway has accepted the
scope.

## HTTP API configuration

Create an authorizer on the HTTP API with these values:

| Setting | Value |
| --- | --- |
| Authorizer type | JWT |
| Identity source | `$request.header.Authorization` |
| Issuer URL | `https://cognito-idp.<region>.amazonaws.com/<user-pool-id>` |
| Audience | The Cognito app-client ID used by this API |

Replace both issuer placeholders with the deployment's AWS region and user
pool ID. The issuer must exactly match the Cognito user-pool issuer URL.
Configure the audience as the app-client ID; do not use a broad or unrelated
audience.

Attach the authorizer only to leader-only routes. Set the route authorization
type to `JWT` and require the corresponding custom scope:

| Route | Required scope |
| --- | --- |
| `GET /v1/admin/engagements` | `saved2serve/leader.read` |
| `PATCH /v1/admin/engagements/{id}` | `saved2serve/leader.write` |

Apply the same policy to any later admin route: least-privilege scope plus the
leader-group check in its Lambda. Leave public routes such as
`POST /v1/engagements`, `GET /v1/churches`, and `GET /v1/devotions` without
this authorizer. The public engagement-creation endpoint accepts submissions;
it must not expose or modify the leader review queue. Keep
`POST /v1/interests` public as well. Do not attach a default authorizer at the
API or stage level that accidentally protects these routes; explicitly set
each public route's authorization type to `NONE` when configuring the HTTP API.

## Lambda authorization contract

For every protected route, inspect the claims supplied by API Gateway in the
HTTP API v2 request context (`requestContext.authorizer.jwt.claims`). Before
repository access or other side effects, require `cognito:groups` to contain
the exact, case-sensitive group name `Leaders`. If the claims or group are
missing, deny the request with a generic `403` response and perform no
operation. Never accept a group name, role, or authorization decision from a
request body, query parameter, or unverified token.

Use `LeaderAuthorization.isAuthorized(event, requiredScope)` at the beginning
of each leader-only handler, after validating that the event is non-null and
before reading from or writing to a repository. The guard fails closed unless
the API Gateway JWT context contains both the exact required scope and the
`Leaders` group. It accepts Cognito's JSON-array group claim and the
single-group string form. A failed check must return a generic `403` response
without performing side effects. The class does not verify JWT signatures:
only trust its context when the Lambda is invoked through the configured API
Gateway JWT authorizer; restrict direct Lambda invocation accordingly.

API Gateway rejects missing, invalid, expired, wrong-issuer, or wrong-audience
tokens with `401`, and rejects a valid token without the route's required scope
with `403`, without invoking the integration. The Lambda group check handles
validly signed, correctly scoped tokens belonging to non-leaders. Do not log
the bearer token, JWT claims, private submission content, or personal data.

## Verification evidence

The public handler tests intentionally invoke each public route without an
`Authorization` header or JWT authorizer context. They verify successful
public behavior without weakening validation or exposing internal data:

| Public route | Handler test | Expected result without token |
| --- | --- | --- |
| `POST /v1/interests` | `InterestApiHandlerTest.acceptsUnauthenticatedPublicRequestWithoutReturningPersonalInformation` | `201`; accepted response does not expose personal fields |
| `POST /v1/engagements` | `EngagementApiHandlerTest.acceptsUnauthenticatedPublicSubmissionAndDoesNotEchoPersonalData` | `201`; submission remains private and pending review |
| `GET /v1/churches` | `ChurchDirectoryApiHandlerTest.returnsOnlyPublicChurchFieldsWithoutBearerTokenForAreaCodeLookup` | `200`; only approved directory fields are returned |
| `GET /v1/devotions` | `DevotionApiHandlerTest.returnsOnlySafePublishedLinkFieldsWithoutBearerToken` | `200`; only public approved-link fields are returned |

`LeaderAuthorizationTest` verifies that the handler guard denies a missing JWT
authorizer context even when an `Authorization` header contains a malformed
bearer token; a raw request header is never treated as verified identity. It
also verifies denial for missing claims, scopes, or leader membership, and
acceptance only when the simulated API Gateway JWT context includes both the
route scope and exact `Leaders` group. These are unit tests of the handler
boundary, not tests of Cognito's signature or expiry validation.

Run the focused leader-guard and public-route tests with:

```powershell
mvn "-DargLine=-Dnet.bytebuddy.experimental=true" "-Dtest=LeaderAuthorizationTest,InterestApiHandlerTest,EngagementApiHandlerTest,ChurchDirectoryApiHandlerTest,DevotionApiHandlerTest" test
```

API Gateway validates JWTs before invoking Lambda. Consequently, test those
gateway behaviors against a deployed non-production HTTP API and user pool;
do not send real tokens to logs, commit them, or paste them into repository
files. Confirm public routes are configured with authorization type `NONE`,
then make unauthenticated requests to each route to ensure they are not
accidentally protected:

```powershell
$api = "https://<api-id>.execute-api.<region>.amazonaws.com"
$missing = curl.exe -sS -o NUL -w "%{http_code}" "$api/v1/admin/engagements"
$invalid = curl.exe -sS -o NUL -w "%{http_code}" `
  -H "Authorization: Bearer not-a-valid-jwt" "$api/v1/admin/engagements"
$churches = curl.exe -sS -o NUL -w "%{http_code}" `
  "$api/v1/churches?areaCode=<approved-area-code>"
$devotions = curl.exe -sS -o NUL -w "%{http_code}" "$api/v1/devotions"
```

For `POST /v1/interests` and `POST /v1/engagements`, send valid synthetic
consented payloads with `Content-Type: application/json` and no authorization
header; verify `201`, no echoed personal data, and no publication. Use only a
non-production environment and synthetic data for these writes.

For valid tokens, obtain short-lived access tokens from the non-production
Cognito app client without printing or persisting the token values. Exercise
the read and write routes using these cases:

| Case | Expected result | Side-effect expectation |
| --- | --- | --- |
| No bearer token | `401` from API Gateway | Lambda is not invoked |
| Malformed, expired, wrong-issuer, or wrong-audience token | `401` from API Gateway | Lambda is not invoked |
| Valid access token without the required route scope | `403` from API Gateway | Lambda is not invoked |
| Valid, correctly scoped access token without `Leaders` membership | Generic `403` from Lambda | No queue data is returned or changed |
| Valid, correctly scoped access token with `Leaders` membership | Route's normal response | Only the requested leader workflow runs |
| Valid leader token on a public route | Public route remains available | No leader-only data is exposed |

Check both read and write routes, including that denied writes leave the
record unchanged. Review API Gateway access-log settings to ensure they do not
record authorization headers, tokens, request bodies, or private query data.
The gateway-level cases require the admin routes and authorizer to be deployed;
they cannot be executed in this repository today because neither is
implemented or configured. No live AWS configuration or deployment test has
been performed.
