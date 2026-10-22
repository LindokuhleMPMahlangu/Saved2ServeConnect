import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import {
  beginLeaderSignIn,
  buildLeaderLogoutUrl,
  clearPendingLeaderSignIn,
  completeLeaderSignIn,
  createCodeChallenge,
  isLeaderSessionExpired,
  validateLeaderAuthConfig
} from "./leader-auth.js";
import { fetchEngagementQueue, LeaderApiError, updateEngagementStatus } from "./leader-api.js";

const config = {
  domain: "https://saved2serve.auth.us-east-1.amazoncognito.com",
  clientId: "public-client-id",
  redirectUri: "https://portal.example.org/leader.html",
  logoutRedirectUri: "https://portal.example.org/leader.html",
  scopes: "saved2serve/leader.read saved2serve/leader.write"
};
const storageMock = () => {
  const values = new Map();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key)
  };
};

test("requires HTTPS Cognito domain, redirect origin, and both leader scopes", () => {
  assert.equal(validateLeaderAuthConfig(config).scopes.length, 2);
  assert.throws(() => validateLeaderAuthConfig({ ...config, domain: "http://cognito.example.org" }), /invalid/);
  assert.throws(() => validateLeaderAuthConfig({ ...config, scopes: "saved2serve/leader.read" }), /scopes/);
  assert.throws(() => validateLeaderAuthConfig({
    ...config,
    logoutRedirectUri: "https://other.example.org/leader.html"
  }), /invalid/);
});

test("expires absent, malformed, and elapsed access-token sessions", () => {
  assert.equal(isLeaderSessionExpired(null, 100), true);
  assert.equal(isLeaderSessionExpired({ expiresAt: Number.NaN }, 100), true);
  assert.equal(isLeaderSessionExpired({ expiresAt: 100 }, 100), true);
  assert.equal(isLeaderSessionExpired({ expiresAt: 101 }, 100), false);
});

test("builds Cognito authorization URL with state and S256 PKCE challenge", async () => {
  const storage = storageMock();
  let navigated;
  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    now: () => 1000,
    location: { assign: (url) => { navigated = new URL(url); } }
  });
  const savedFlow = JSON.parse(storage.values.get("saved2serve.leader.oauth"));
  assert.equal(navigated.origin, new URL(config.domain).origin);
  assert.equal(navigated.pathname, "/oauth2/authorize");
  assert.equal(navigated.searchParams.get("response_type"), "code");
  assert.equal(navigated.searchParams.get("client_id"), config.clientId);
  assert.equal(navigated.searchParams.get("code_challenge_method"), "S256");
  assert.equal(navigated.searchParams.get("state"), savedFlow.state);
  assert.equal(navigated.searchParams.get("code_challenge"), await createCodeChallenge(savedFlow.verifier, webcrypto));
  assert.equal(navigated.searchParams.has("code_verifier"), false);
  assert.equal(savedFlow.state.length >= 40, true);
});

test("exchanges verified one-time authorization code and keeps access token out of storage", async () => {
  const storage = storageMock();
  let postedBody;
  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    now: () => 5000,
    location: { assign: () => {} }
  });
  const flow = JSON.parse(storage.values.get("saved2serve.leader.oauth"));
  let cleanedUrl;
  const session = await completeLeaderSignIn(config, {
    storage,
    now: () => 6000,
    location: { href: `${config.redirectUri}?code=one-time-code&state=${flow.state}` },
    history: { replaceState: (_state, _title, url) => { cleanedUrl = url; } },
    fetchImpl: async (url, options) => {
      assert.equal(url, `${config.domain}/oauth2/token`);
      assert.equal(options.credentials, "omit");
      assert.equal(options.referrerPolicy, "no-referrer");
      postedBody = new URLSearchParams(options.body);
      return {
        ok: true,
        json: async () => ({ token_type: "Bearer", access_token: "volatile-access-token", expires_in: 3600 })
      };
    }
  });
  assert.equal(cleanedUrl, "/leader.html");
  assert.equal(postedBody.get("code"), "one-time-code");
  assert.equal(postedBody.get("code_verifier"), flow.verifier);
  assert.equal(session.accessToken, "volatile-access-token");
  assert.equal(session.expiresAt, 3_606_000);
  assert.equal(storage.values.has("saved2serve.leader.oauth"), false);
  assert.equal([...storage.values.values()].some((value) => value.includes("volatile-access-token")), false);
});

test("rejects callback state mismatch and expired authorization flow", async () => {
  const storage = storageMock();
  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    now: () => 0,
    location: { assign: () => {} }
  });
  await assert.rejects(completeLeaderSignIn(config, {
    storage,
    now: () => 100,
    location: { href: `${config.redirectUri}?code=code&state=wrong-state` },
    history: { replaceState: () => {} },
    fetchImpl: async () => assert.fail("mismatched state must not exchange a code")
  }), /could not be verified/);

  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    now: () => 0,
    location: { assign: () => {} }
  });
  const flow = JSON.parse(storage.values.get("saved2serve.leader.oauth"));
  await assert.rejects(completeLeaderSignIn(config, {
    storage,
    now: () => 301_000,
    location: { href: `${config.redirectUri}?code=code&state=${flow.state}` },
    history: { replaceState: () => {} },
    fetchImpl: async () => assert.fail("expired state must not exchange a code")
  }), /could not be verified/);
});

test("handles denied OAuth callback, malformed token response, and Cognito logout", async () => {
  const storage = storageMock();
  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    location: { assign: () => {} }
  });
  await assert.rejects(completeLeaderSignIn(config, {
    storage,
    location: { href: `${config.redirectUri}?error=access_denied` },
    history: { replaceState: () => {} }
  }), /not completed/);

  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    location: { assign: () => {} }
  });
  const flow = JSON.parse(storage.values.get("saved2serve.leader.oauth"));
  await assert.rejects(completeLeaderSignIn(config, {
    storage,
    location: { href: `${config.redirectUri}?code=code&state=${flow.state}` },
    history: { replaceState: () => {} },
    fetchImpl: async () => ({ ok: true, json: async () => ({ access_token: "not-bearer" }) })
  }), /invalid session/);

  const logout = new URL(buildLeaderLogoutUrl(config));
  assert.equal(logout.pathname, "/logout");
  assert.equal(logout.searchParams.get("client_id"), config.clientId);
  assert.equal(logout.searchParams.get("logout_uri"), config.logoutRedirectUri);
});

test("removes pending PKCE state on sign out and masks identity-service network errors", async () => {
  const storage = storageMock();
  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    location: { assign: () => {} }
  });
  assert.equal(storage.values.has("saved2serve.leader.oauth"), true);
  clearPendingLeaderSignIn(storage);
  assert.equal(storage.values.has("saved2serve.leader.oauth"), false);

  await beginLeaderSignIn(config, {
    storage,
    cryptoApi: webcrypto,
    location: { assign: () => {} }
  });
  const flow = JSON.parse(storage.values.get("saved2serve.leader.oauth"));
  await assert.rejects(completeLeaderSignIn(config, {
    storage,
    location: { href: `${config.redirectUri}?code=code&state=${flow.state}` },
    history: { replaceState: () => {} },
    fetchImpl: async () => { throw new TypeError("network detail"); }
  }), (error) => error.message === "Leader sign-in could not reach the identity service. Please try again.");
});

const validId = "11111111-1111-4111-8111-111111111111";
const privateItem = {
  engagementId: validId,
  createdAt: "2026-10-26T10:00:00Z",
  name: "Leader review",
  email: "private@example.org",
  areaCode: "00180",
  phone: "",
  type: "HELP_REQUEST",
  message: "Private submission",
  status: "PENDING_REVIEW",
  reviewerId: "must-not-project"
};

test("loads queue with bearer token, filters, cursor, and no caching", async () => {
  let request;
  const page = await fetchEngagementQueue("leader-access-token", {
    apiBaseUrl: "https://api.example.org/",
    status: "PENDING_REVIEW",
    limit: 25,
    cursor: "opaque cursor",
    fetchImpl: async (url, options) => {
      request = { url, options };
      return { ok: true, json: async () => ({ items: [privateItem], nextCursor: null }) };
    }
  });
  const url = new URL(request.url);
  assert.equal(url.pathname, "/v1/admin/engagements");
  assert.equal(url.searchParams.get("status"), "PENDING_REVIEW");
  assert.equal(url.searchParams.get("cursor"), "opaque cursor");
  assert.equal(request.options.headers.Authorization, "Bearer leader-access-token");
  assert.equal(request.options.cache, "no-store");
  assert.equal(request.options.credentials, "omit");
  assert.equal(page.items[0].message, "Private submission");
  assert.equal("reviewerId" in page.items[0], false);
});

test("sends protected review PATCH and maps authorization and transition failures safely", async () => {
  let request;
  const result = await updateEngagementStatus("leader-token", validId, "APPROVED", {
    apiBaseUrl: "https://api.example.org",
    fetchImpl: async (url, options) => {
      request = { url, options };
      return { ok: true, json: async () => ({ status: "APPROVED" }) };
    }
  });
  assert.equal(result, "APPROVED");
  assert.equal(request.url, `https://api.example.org/v1/admin/engagements/${validId}`);
  assert.equal(request.options.method, "PATCH");
  assert.equal(request.options.headers.Authorization, "Bearer leader-token");
  assert.deepEqual(JSON.parse(request.options.body), { status: "APPROVED" });
  await assert.rejects(
    updateEngagementStatus("leader-token", validId, "REJECTED", {
      fetchImpl: async () => ({ ok: false, status: 403, text: async () => "private response" })
    }),
    (error) => error instanceof LeaderApiError
      && error.status === 403
      && !error.message.includes("private response")
  );
  await assert.rejects(
    fetchEngagementQueue("leader-token", {
      fetchImpl: async () => ({ ok: false, status: 401 })
    }),
    (error) => error.status === 401 && /session has expired/.test(error.message)
  );
});
