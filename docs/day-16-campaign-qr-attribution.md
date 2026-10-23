# Day 16: Campaign QR code and attribution

## QR destination

The public landing page creates a QR code locally in the browser using the
self-hosted `web/qrcode.js` library. It encodes the current page's public
origin and path, with existing query and fragment values removed, followed by
`?campaign=community-outreach#interest-form`. This opens the public interest
form directly. The SVG can be downloaded from the page for campaign materials.
No QR-generation service receives the URL.

The QR code is generated from the address used to open the page. Before
printing or distributing it, deploy the public site over HTTPS, open its
production URL, download the QR code, and scan it to confirm the destination.
Local preview and test URLs are not suitable for public materials.

## Attribution and privacy

The only accepted campaign identifier is `community-outreach`. A valid,
single `campaign` query parameter is mapped to the existing API `source` field
as `campaign-qr:community-outreach`; a missing, repeated, or unknown value
uses `landing-page`. The identifier is passed in the registration JSON body
and persisted through the existing source field. The API validates source
values against the supported set (`landing-page`, `qr-code`, and
`campaign-qr:community-outreach`, while retaining the existing empty-source
behavior); arbitrary values such as email addresses are rejected before
persistence. No API or database fields are added.

Campaign identifiers are fixed, non-personal metadata. No name, email,
postcode, consent choice, or other form value is added to the QR URL, browser
storage, or logs. The QR URL contains only the campaign identifier. The form
still submits personal data only after the user provides the existing required
consent, and optional follow-up consent remains separate.

## Verification

Run the public-site tests with Node.js 18 or newer:

```powershell
npm --prefix web test
```

Coverage includes campaign ID allowlisting, query cleanup, the exact QR
destination and generated SVG, the bounded frontend payload and API source
validation, and existing registration behavior. Browser-check the downloaded
SVG with a QR reader against the production HTTPS URL before printing. This
change does not configure or deploy static hosting or AWS resources.
