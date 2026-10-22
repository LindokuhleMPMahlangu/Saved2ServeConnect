const FLOW_KEY = "saved2serve.leader.oauth";
const FLOW_MAX_AGE_MS = 5 * 60 * 1000;

export function isLeaderSessionExpired(session, now = Date.now()) {
  return !session || !Number.isFinite(session.expiresAt) || session.expiresAt <= now;
}

export function clearPendingLeaderSignIn(storage = globalThis.sessionStorage) {
  storage.removeItem(FLOW_KEY);
}

export function validateLeaderAuthConfig(config) {
  if (!config || !config.domain || !config.clientId || !config.redirectUri || !config.logoutRedirectUri) {
    throw new Error("Leader sign-in is not configured.");
  }
  const domain = new URL(config.domain);
  const redirectUri = new URL(config.redirectUri);
  const logoutRedirectUri = new URL(config.logoutRedirectUri);
  if (domain.protocol !== "https:" || !domain.hostname
      || redirectUri.origin !== logoutRedirectUri.origin
      || !["https:", "http:"].includes(redirectUri.protocol)
      || (redirectUri.protocol === "http:" && !["localhost", "127.0.0.1"].includes(redirectUri.hostname))) {
    throw new Error("Leader sign-in configuration is invalid.");
  }
  const scopes = String(config.scopes ?? "").split(/\s+/).filter(Boolean);
  if (!scopes.includes("saved2serve/leader.read") || !scopes.includes("saved2serve/leader.write")) {
    throw new Error("Leader read and write scopes must be configured.");
  }
  return { domain: domain.origin, clientId: config.clientId, redirectUri: redirectUri.href,
    logoutRedirectUri: logoutRedirectUri.href, scopes };
}

export async function createCodeChallenge(verifier, cryptoApi = globalThis.crypto) {
  const digest = await cryptoApi.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return encodeBase64Url(new Uint8Array(digest));
}

export async function beginLeaderSignIn(config, {
  storage = globalThis.sessionStorage,
  cryptoApi = globalThis.crypto,
  location = globalThis.location,
  now = Date.now
} = {}) {
  const auth = validateLeaderAuthConfig(config);
  if (!cryptoApi?.getRandomValues || !cryptoApi?.subtle) {
    throw new Error("Secure leader sign-in is unavailable in this browser.");
  }
  const state = randomBase64Url(32, cryptoApi);
  const verifier = randomBase64Url(64, cryptoApi);
  const challenge = await createCodeChallenge(verifier, cryptoApi);
  storage.setItem(FLOW_KEY, JSON.stringify({ state, verifier, createdAt: now() }));

  const authorizeUrl = new URL("/oauth2/authorize", auth.domain);
  authorizeUrl.search = new URLSearchParams({
    response_type: "code",
    client_id: auth.clientId,
    redirect_uri: auth.redirectUri,
    scope: auth.scopes.join(" "),
    state,
    code_challenge: challenge,
    code_challenge_method: "S256"
  }).toString();
  location.assign(authorizeUrl.href);
}

export async function completeLeaderSignIn(config, {
  storage = globalThis.sessionStorage,
  fetchImpl = globalThis.fetch,
  location = globalThis.location,
  history = globalThis.history,
  now = Date.now
} = {}) {
  const callbackUrl = new URL(location.href);
  const code = callbackUrl.searchParams.get("code");
  const returnedState = callbackUrl.searchParams.get("state");
  const oauthError = callbackUrl.searchParams.get("error");
  if (!code && !returnedState && !oauthError) {
    return null;
  }
  history.replaceState(null, "", callbackUrl.pathname + callbackUrl.hash);
  if (oauthError) {
    storage.removeItem(FLOW_KEY);
    throw new Error("Leader sign-in was not completed. Please try again.");
  }
  const auth = validateLeaderAuthConfig(config);
  const expectedRedirect = new URL(auth.redirectUri);
  if (callbackUrl.origin !== expectedRedirect.origin || callbackUrl.pathname !== expectedRedirect.pathname) {
    storage.removeItem(FLOW_KEY);
    throw new Error("Leader sign-in could not be verified. Please sign in again.");
  }
  const rawFlow = storage.getItem(FLOW_KEY);
  storage.removeItem(FLOW_KEY);
  if (!rawFlow || !code || !returnedState) {
    throw new Error("Leader sign-in could not be verified. Please sign in again.");
  }

  let flow;
  try {
    flow = JSON.parse(rawFlow);
  } catch {
    throw new Error("Leader sign-in could not be verified. Please sign in again.");
  }
  if (typeof flow.state !== "string" || typeof flow.verifier !== "string"
      || !Number.isFinite(flow.createdAt) || now() - flow.createdAt > FLOW_MAX_AGE_MS
      || now() < flow.createdAt || !constantTimeEquals(flow.state, returnedState)) {
    throw new Error("Leader sign-in could not be verified. Please sign in again.");
  }

  let response;
  try {
    response = await fetchImpl(`${auth.domain}/oauth2/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        client_id: auth.clientId,
        code,
        redirect_uri: auth.redirectUri,
        code_verifier: flow.verifier
      }),
      credentials: "omit",
      referrerPolicy: "no-referrer"
    });
  } catch {
    throw new Error("Leader sign-in could not reach the identity service. Please try again.");
  }
  if (!response.ok) {
    throw new Error("Leader sign-in could not be completed. Please try again.");
  }
  let tokenResponse;
  try {
    tokenResponse = await response.json();
  } catch {
    throw new Error("Leader sign-in returned an invalid session. Please try again.");
  }
  if (!tokenResponse || tokenResponse.token_type?.toLowerCase() !== "bearer"
      || typeof tokenResponse.access_token !== "string"
      || !tokenResponse.access_token
      || !Number.isFinite(tokenResponse.expires_in)
      || tokenResponse.expires_in <= 0) {
    throw new Error("Leader sign-in returned an invalid session. Please try again.");
  }
  return {
    accessToken: tokenResponse.access_token,
    expiresAt: now() + tokenResponse.expires_in * 1000
  };
}

export function buildLeaderLogoutUrl(config) {
  const auth = validateLeaderAuthConfig(config);
  const logoutUrl = new URL("/logout", auth.domain);
  logoutUrl.search = new URLSearchParams({
    client_id: auth.clientId,
    logout_uri: auth.logoutRedirectUri
  }).toString();
  return logoutUrl.href;
}

function randomBase64Url(length, cryptoApi) {
  const bytes = new Uint8Array(length);
  cryptoApi.getRandomValues(bytes);
  return encodeBase64Url(bytes);
}

function encodeBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function constantTimeEquals(left, right) {
  if (left.length !== right.length) {
    return false;
  }
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}
