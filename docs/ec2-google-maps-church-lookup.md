# EC2 Google Maps church lookup demo

The EC2 demo's `GET /v1/churches?areaCode=...` endpoint sends a direct Google
Places Text Search for churches near the submitted area or South African
postcode, with Johannesburg, South Africa as the search context. This demo does
not use the SAM API's curated, approved directory and does not read or write the
DynamoDB registration table.

## Search behavior and data handling

- Accepts one South African four-digit postcode or a short area name.
- Searches for Google Places entries of type `church`. Results are best-effort
  matches for "churches near [area]" and are not guaranteed to be exhaustive or
  within a specific distance.
- Returns the place ID, display name, formatted address, and Google Maps link.
  Place IDs are the only Google Places field exempt from Google's caching
  restrictions. Google Places search results are not cached by this service.
- Sends only the area/postcode search to map providers, never registration
  details or contact information.
- Returns generic `400` input errors and `503` provider failures. Logs include
  provider name and HTTP status/type only; never the API key or submitted form
  values.

Church listings and their details can be incomplete or out of date. They are
not verified or endorsed by Saved2Serve; visitors should confirm details before
travelling or contacting a listed church.

## Google Cloud setup

Google Places requires a Google Cloud project with billing enabled and the
**Places API (New)** enabled. Create an API key restricted to the Places API.
Because requests originate from EC2, use an IP-address restriction matching
the instance's stable public egress IP where available. Do not put this key in
`web/config.js`, HTML, JavaScript, GitHub, or a chat message.

After deploying the installer once, enter the key securely on EC2 Instance
Connect. This command prompts without echoing the key and writes it to a
root-only file:

```bash
sudo bash -c 'read -rsp "Google Maps API key: " key; echo; printf "GOOGLE_MAPS_API_KEY=%s\n" "$key" > /etc/saved2serve-demo-api.env; chmod 600 /etc/saved2serve-demo-api.env; unset key'
sudo systemctl restart saved2serve-demo-api
```

Verify that the service is active without printing its environment (which
contains the secret):

```bash
sudo systemctl status saved2serve-demo-api --no-pager -l
```

Google Maps Platform billing is usage-based. Set a small API quota and billing
alerts in Google Cloud before public testing; an alert is not necessarily a
hard spending cap. The API serializes Google search requests and Nginx
rate-limits requests per client IP, but those controls do not replace Cloud
quotas or key restrictions.

The website shows Google Maps text attribution and links each result to Google
Maps. Refer to the current
[Places API policies](https://developers.google.com/maps/documentation/places/web-service/policies)
and [Text Search documentation](https://developers.google.com/maps/documentation/places/web-service/text-search).

## Deploy and verify on EC2

After pushing changes to the `main` branch, rerun the installer from EC2
Instance Connect:

```bash
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/LindokuhleMPMahlangu/Saved2ServeConnect/main/infra/install-ec2-demo-interest-api.sh \
  --output /tmp/install-ec2-demo-interest-api.sh
sudo bash /tmp/install-ec2-demo-interest-api.sh Saved2ServeDemoRegistrations eu-north-1
```

Create the API key environment file as described above, then check the route
locally before testing through the HTTPS tunnel:

```bash
curl --fail --show-error --get http://127.0.0.1/v1/churches \
  --data-urlencode 'areaCode=2001'
```

A successful request returns JSON with `source: "Google Maps"` and a
`churches` array, which may be empty.

Run local tests with:

```bash
python -m unittest infra.test_ec2_demo_interest_api
node --test web/app.test.js
```
