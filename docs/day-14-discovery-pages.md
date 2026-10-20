# Day 14: Church and devotion discovery pages

## Church discovery

The `#discover-churches` section accepts a coarse area/postcode string and
requests `GET /v1/churches?areaCode=<exact-code>`. Surrounding whitespace is
trimmed, while the code is kept as text so leading zeroes are preserved.
Matching is exact; the client never geolocates a visitor, infers a nearby
area, or broadens a no-results lookup.

The API service is responsible for returning only approved church records for
that exact service area. The page makes a second defensive projection to
public church ID, display name, explicitly public website, and explicitly
public address fields; it drops review metadata and records marked with a
non-approved status. Church names and addresses are inserted as text, not
HTML. Website links must use HTTPS.

## Weekly devotion discovery

The page requests `GET /v1/devotions` and renders the published `devotions`
collection. The API service projects approved links to devotion ID, title,
URL, and platform. The browser only renders entries with the expected public
fields, `YOUTUBE` platform, and an HTTPS URL on an approved YouTube host; it
also excludes explicit non-approved status values. Titles use text nodes,
never raw HTML. External links open with `noopener noreferrer`.

The client is a defensive display layer; it does not replace server-side
approval checks. The routes and service implementations remain the authority
for filtering directory and devotion data.

## Loading, empty, and error behavior

Devotions show a loading message while fetched on page load. Church results
show an initial prompt until an area is entered. Both flows announce loading,
empty, and failure messages in live status regions. A successful empty result
does not broaden its search. A failed request shows a generic message and a
retry button; raw server error bodies are not displayed.

## Configuration and hosting

Set `apiBaseUrl` in `web/config.js` to the HTTPS API base. The client uses
`churchesPath` and `devotionsPath` for the existing read-only routes. Configure
API Gateway CORS for the exact static-site origin, `GET` and `POST`, and the
`Accept` and `Content-Type` headers. Avoid logging the church route query
string, which contains the submitted area code, and do not log response
content. No static hosting, API CORS, or AWS resources are provisioned here.

## Verification

Run the dependency-free frontend tests with Node.js 18 or newer:

```powershell
npm --prefix web test
```

Coverage includes exact area-code encoding and leading zeroes, public-field
projection, pending/unapproved record exclusion, unsafe URL rejection,
generic HTTP failure handling, and previously covered interest form behavior.
Browser preview also exercises small-screen layouts, and should be reviewed
after any styling or API-contract change.
