import test from "node:test";
import assert from "node:assert/strict";
import qrcode from "qrcode-generator";
import {
  buildCampaignLandingUrl,
  buildInterestPayload,
  CAMPAIGN_ID,
  createCampaignQrDataUrl,
  fetchChurches,
  fetchDevotions,
  getCampaignId,
  getRegistrationMessage,
  isApprovedVideoUrl,
  projectPublicChurches,
  projectPublishedDevotions,
  RegistrationError,
  submitInterest,
  validateInterestInput
} from "./app.js";

const validValues = {
  name: "Avery Example",
  email: "avery@example.org",
  areaCode: "00180",
  phone: "",
  consent: true,
  followUpConsent: false
};

test("validates the required registration fields without requiring optional follow-up", () => {
  assert.equal(validateInterestInput(validValues), "");
  assert.equal(validateInterestInput({ ...validValues, name: " " }), "Please enter your name.");
  assert.equal(validateInterestInput({ ...validValues, email: "not-an-email" }),
    "Please enter a valid email address.");
  assert.equal(validateInterestInput({ ...validValues, areaCode: "" }),
    "Please enter your area or postcode.");
  assert.match(validateInterestInput({ ...validValues, consent: false }), /required registration consent/);
});

test("creates only allowlisted form fields and keeps follow-up consent separate", () => {
  const payload = buildInterestPayload(validValues, "attempt-id");
  assert.deepEqual(payload, {
    idempotencyKey: "attempt-id",
    name: "Avery Example",
    email: "avery@example.org",
    areaCode: "00180",
    consent: true,
    source: "landing-page",
    followUpConsent: false
  });
  assert.equal("address" in payload, false);
  assert.equal("latitude" in payload, false);
  assert.equal("longitude" in payload, false);
  assert.equal("phone" in payload, false);

  const withPhone = buildInterestPayload({ ...validValues, phone: "5550100100", followUpConsent: true }, "retry");
  assert.equal(withPhone.phone, "5550100100");
  assert.equal(withPhone.followUpConsent, true);
});

test("captures only the allowlisted campaign identifier in the existing source field", () => {
  assert.equal(getCampaignId("?campaign=community-outreach"), CAMPAIGN_ID);
  assert.equal(getCampaignId("?campaign=unknown-campaign"), "");
  assert.equal(getCampaignId("?campaign=community-outreach&campaign=unknown-campaign"), "");
  assert.equal(getCampaignId("?campaign=person%40example.org"), "");

  const payload = buildInterestPayload(validValues, "attempt-id", CAMPAIGN_ID);
  assert.equal(payload.source, "campaign-qr:community-outreach");
  assert.deepEqual(Object.keys(payload).sort(), [
    "areaCode",
    "consent",
    "email",
    "followUpConsent",
    "idempotencyKey",
    "name",
    "source"
  ]);
  assert.equal(buildInterestPayload(validValues, "attempt-id", "unexpected").source, "landing-page");
});

test("campaign QR targets the public interest form without forwarding other query data", () => {
  const destination = buildCampaignLandingUrl(
    "https://saved2serve.example/community/?email=private%40example.org#top"
  );
  assert.equal(
    destination,
    "https://saved2serve.example/community/?campaign=community-outreach#interest-form"
  );
  assert.throws(() => buildCampaignLandingUrl("file:///tmp/index.html"), /public HTTP or HTTPS page/);
  assert.throws(() => buildCampaignLandingUrl("https://saved2serve.example", "unapproved"), /invalid/);

  const dataUrl = createCampaignQrDataUrl(destination, qrcode);
  assert.match(dataUrl, /^data:image\/svg\+xml;charset=utf-8,/);
  const svg = decodeURIComponent(dataUrl.split(",", 2)[1]);
  const expectedQr = qrcode(0, "M");
  expectedQr.addData(destination);
  expectedQr.make();
  assert.equal(svg, expectedQr.createSvgTag(5, 4));
  assert.ok(expectedQr.getModuleCount() > 21);
});

test("posts JSON to the configured API without credentials or referrer", async () => {
  let request;
  const fetchImpl = async (url, options) => {
    request = { url, options };
    return { ok: true, status: 201 };
  };
  await submitInterest(buildInterestPayload(validValues, "attempt-id"), {
    apiBaseUrl: "https://api.example.org/",
    fetchImpl
  });
  assert.equal(request.url, "https://api.example.org/v1/interests");
  assert.equal(request.options.method, "POST");
  assert.equal(request.options.headers["Content-Type"], "application/json");
  assert.equal(request.options.credentials, "omit");
  assert.equal(request.options.referrerPolicy, "no-referrer");
  assert.deepEqual(JSON.parse(request.options.body), buildInterestPayload(validValues, "attempt-id"));
});

test("uses safe retry messages for API failures and network errors", async () => {
  for (const status of [400, 409, 429, 503, 500]) {
    await assert.rejects(
      submitInterest({}, { fetchImpl: async () => ({ ok: false, status }) }),
      (error) => error instanceof RegistrationError
        && error.status === status
        && error.message === getRegistrationMessage(status)
    );
  }
  await assert.rejects(
    submitInterest({}, { fetchImpl: async () => { throw new TypeError("network"); } }),
    TypeError
  );
  assert.match(getRegistrationMessage(503), /try again shortly/);
});

test("church lookup preserves the exact area code and projects only public fields", async () => {
  let request;
  const churches = await fetchChurches(" 00180 ", {
    apiBaseUrl: "https://api.example.org/",
    fetchImpl: async (url, options) => {
      request = { url, options };
      return {
        ok: true,
        json: async () => ({
          churches: [{
            churchId: "church-1",
            displayName: "Community Church",
            publicWebsite: "https://church.example.org",
            publicAddress: "Approved public venue",
            status: "APPROVED",
            sourceReference: "internal-only",
            reviewerId: "reviewer-only"
          }]
        })
      };
    }
  });
  assert.equal(request.url, "https://api.example.org/v1/churches?areaCode=00180");
  assert.equal(request.options.method, "GET");
  assert.equal(request.options.credentials, "omit");
  assert.deepEqual(churches, [{
    churchId: "church-1",
    displayName: "Community Church",
    publicWebsite: "https://church.example.org/",
    publicAddress: "Approved public venue"
  }]);
  assert.equal("sourceReference" in churches[0], false);
  assert.equal("reviewerId" in churches[0], false);
});

test("church projection excludes pending content and unsafe website URLs", () => {
  const churches = projectPublicChurches({
    churches: [
      { churchId: "approved", displayName: "Approved", status: "APPROVED", publicWebsite: "javascript:alert(1)" },
      { churchId: "pending", displayName: "Pending", status: "PENDING_REVIEW" },
      { churchId: "", displayName: "Missing identity" }
    ]
  });
  assert.deepEqual(churches, [{
    churchId: "approved",
    displayName: "Approved",
    publicWebsite: "",
    publicAddress: ""
  }]);
});

test("devotion discovery shows only approved public YouTube links", async () => {
  let url;
  const devotions = await fetchDevotions({
    apiBaseUrl: "https://api.example.org",
    fetchImpl: async (requestUrl) => {
      url = requestUrl;
      return {
        ok: true,
        json: async () => ({
          devotions: [
            { devotionId: "d-1", title: "A quiet beginning", url: "https://youtu.be/abc", platform: "YOUTUBE" },
            { devotionId: "d-2", title: "Pending", url: "https://youtube.com/watch?v=def", platform: "YOUTUBE", status: "PENDING_REVIEW" },
            { devotionId: "d-3", title: "Unsafe", url: "https://example.org/video", platform: "OTHER" }
          ]
        })
      };
    }
  });
  assert.equal(url, "https://api.example.org/v1/devotions");
  assert.deepEqual(devotions, [{
    devotionId: "d-1",
    title: "A quiet beginning",
    url: "https://youtu.be/abc",
    platform: "YOUTUBE"
  }]);
  assert.equal(isApprovedVideoUrl("https://www.youtube.com/watch?v=abc"), true);
  assert.equal(isApprovedVideoUrl("http://youtube.com/watch?v=abc"), false);
  assert.equal(isApprovedVideoUrl("https://youtube.com.example.net/watch?v=abc"), false);
  assert.throws(() => projectPublishedDevotions({ results: [] }), /response is invalid/);
});

test("discovery HTTP failures return generic errors without response body details", async () => {
  await assert.rejects(
    fetchDevotions({ fetchImpl: async () => ({ ok: false, status: 503 }) }),
    (error) => error.name === "DiscoveryError"
      && error.status === 503
      && !error.message.includes("503")
  );
});
