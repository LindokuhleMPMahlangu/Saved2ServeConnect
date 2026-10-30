# EC2 OpenStreetMap church lookup demo

The EC2 demo's `GET /v1/churches?areaCode=...` endpoint searches OpenStreetMap
map data. It does not read or write the DynamoDB registration table and does
not use the SAM API's curated, approved church directory.

## Search behavior

- Accepts one South African four-digit postcode or a short area name. Area
  names are geocoded within South Africa's Johannesburg pilot context.
- Finds named Christian places of worship within 5 km of the geocoded result.
- Returns a public name, any mapped public address/HTTPS website, and a link
  to the OpenStreetMap object. Unnamed map objects are excluded.
- Caches results in the API process for one hour. The cache is temporary and
  is cleared when the process restarts.
- Sends only the area/postcode search to the map provider, never registration
  details or contact information.
- Returns generic `400` input errors and `503` provider failures. Provider
  exception details are not returned to the browser.

OpenStreetMap entries are community-maintained and can be missing, outdated,
or incorrect. They are not verified or endorsed by Save2Serve. Visitors should
confirm details before travelling or contacting a listed church.

## Provider requirements

The browser calls the same-origin EC2 endpoint; EC2 proxies the geocoding and
church search, so browser visitors do not call the public services directly.
The API identifies itself with a `Saved2ServeConnect` User-Agent, caches
responses, and serializes uncached Nominatim lookups to keep them at or below
one request per second. Nginx also rate-limits the public church endpoint.

The public Nominatim server is a limited community service, not a production
SLA. Follow its current [usage policy](https://operations.osmfoundation.org/policies/nominatim/);
do not use it for bulk/systematic searches or submit personal/confidential
information. For production or sustained traffic, choose an appropriate
provider or operate a service instance and configure the endpoint URLs in the
systemd service environment.

The page displays attribution to
[OpenStreetMap contributors](https://www.openstreetmap.org/copyright) and links
the [Open Database License](https://opendatacommons.org/licenses/odbl/).

## Deploy and verify on EC2

After pushing changes to the `main` branch, rerun the existing installer from
EC2 Instance Connect:

```bash
curl --fail --location --silent --show-error \
  https://raw.githubusercontent.com/LindokuhleMPMahlangu/Saved2ServeConnect/main/infra/install-ec2-demo-interest-api.sh \
  --output /tmp/install-ec2-demo-interest-api.sh
sudo bash /tmp/install-ec2-demo-interest-api.sh Saved2ServeDemoRegistrations eu-north-1
```

Check the route locally before testing through the HTTPS tunnel:

```bash
curl --fail --show-error --get http://127.0.0.1/v1/churches \
  --data-urlencode 'areaCode=2001'
```

A successful request returns JSON with `source: "OpenStreetMap"` and a
`churches` array (which may be empty). Try a different area if there are no
mapped churches nearby. The public dataset is not guaranteed to contain every
church.

Run the local tests with:

```bash
python -m unittest infra.test_ec2_demo_interest_api
node --test web/app.test.js
```
