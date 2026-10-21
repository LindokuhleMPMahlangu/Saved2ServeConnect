import test from "node:test";
import assert from "node:assert/strict";
import {
  buildInterestPayload,
  getRegistrationMessage,
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
