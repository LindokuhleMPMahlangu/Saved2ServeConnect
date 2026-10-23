const API_PATH = "/v1/interests";
const CHURCHES_PATH = "/v1/churches";
const DEVOTIONS_PATH = "/v1/devotions";
const APPROVED_VIDEO_HOSTS = new Set(["youtube.com", "www.youtube.com", "youtu.be", "www.youtu.be"]);
export const CAMPAIGN_ID = "community-outreach";
const CAMPAIGN_SOURCE_PREFIX = "campaign-qr:";
const CAMPAIGN_SOURCE_KEY = "campaign";

export function validateInterestInput({ name, email, areaCode, consent }) {
  if (!name?.trim()) {
    return "Please enter your name.";
  }
  if (!email?.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
    return "Please enter a valid email address.";
  }
  if (!areaCode?.trim()) {
    return "Please enter your area or postcode.";
  }
  if (!consent) {
    return "Please agree to the required registration consent.";
  }
  return "";
}

export function getCampaignId(search = "") {
  const campaignIds = new URLSearchParams(search).getAll(CAMPAIGN_SOURCE_KEY);
  return campaignIds.length === 1 && campaignIds[0] === CAMPAIGN_ID ? CAMPAIGN_ID : "";
}

export function buildCampaignLandingUrl(pageUrl, campaignId = CAMPAIGN_ID) {
  if (campaignId !== CAMPAIGN_ID) {
    throw new Error("Campaign QR configuration is invalid.");
  }
  const destination = new URL(pageUrl);
  if (!["http:", "https:"].includes(destination.protocol)
    || destination.username || destination.password) {
    throw new Error("Campaign QR requires a public HTTP or HTTPS page.");
  }
  destination.search = "";
  destination.searchParams.set(CAMPAIGN_SOURCE_KEY, campaignId);
  destination.hash = "interest-form";
  return destination.href;
}

export function createCampaignQrDataUrl(destinationUrl, qrGenerator = globalThis.qrcode) {
  if (typeof qrGenerator !== "function") {
    throw new Error("Campaign QR generator is unavailable.");
  }
  const qr = qrGenerator(0, "M");
  qr.addData(destinationUrl);
  qr.make();
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(qr.createSvgTag(5, 4))}`;
}

export function buildInterestPayload(values, idempotencyKey, campaignId = "") {
  const payload = {
    idempotencyKey,
    name: values.name.trim(),
    email: values.email.trim(),
    areaCode: values.areaCode.trim(),
    consent: values.consent,
    source: campaignId === CAMPAIGN_ID
      ? `${CAMPAIGN_SOURCE_PREFIX}${campaignId}`
      : "landing-page",
    followUpConsent: values.followUpConsent
  };
  if (values.phone.trim()) {
    payload.phone = values.phone.trim();
  }
  return payload;
}

export function getRegistrationMessage(status) {
  if (status === 409) {
    return "We couldn't accept this registration. Please check your details or try again later.";
  }
  if (status === 429) {
    return "We're receiving requests quickly. Please wait a moment and try again.";
  }
  if (status === 503) {
    return "We couldn't complete your registration right now. Your details are still here—please try again shortly.";
  }
  if (status === 400) {
    return "Some information needs attention. Please review the form and try again.";
  }
  return "We couldn't send your registration just now. Please try again shortly.";
}

export async function submitInterest(payload, {
  apiBaseUrl = "",
  fetchImpl = globalThis.fetch
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("Registration service is unavailable.");
  }
  const base = apiBaseUrl.trim().replace(/\/+$/, "");
  const response = await fetchImpl(`${base}${API_PATH}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    credentials: "omit",
    referrerPolicy: "no-referrer"
  });
  if (!response.ok) {
    throw new RegistrationError(response.status, getRegistrationMessage(response.status));
  }
  return true;
}

export class RegistrationError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "RegistrationError";
    this.status = status;
  }
}

export function projectPublicChurches(responseBody) {
  if (!responseBody || !Array.isArray(responseBody.churches)) {
    throw new Error("Church directory response is invalid.");
  }
  return responseBody.churches
    .filter((church) => church && typeof church === "object"
      && (!("status" in church) || church.status === "APPROVED")
      && typeof church.churchId === "string"
      && typeof church.displayName === "string"
      && church.churchId.trim()
      && church.displayName.trim())
    .map((church) => ({
      churchId: church.churchId.trim(),
      displayName: church.displayName.trim(),
      publicWebsite: safeHttpsUrl(church.publicWebsite),
      publicAddress: typeof church.publicAddress === "string" ? church.publicAddress.trim() : ""
    }));
}

export function projectPublishedDevotions(responseBody) {
  if (!responseBody || !Array.isArray(responseBody.devotions)) {
    throw new Error("Devotion response is invalid.");
  }
  return responseBody.devotions
    .filter((devotion) => devotion && typeof devotion === "object"
      && (!("status" in devotion) || devotion.status === "APPROVED")
      && typeof devotion.devotionId === "string"
      && typeof devotion.title === "string"
      && typeof devotion.url === "string"
      && typeof devotion.platform === "string"
      && devotion.devotionId.trim()
      && devotion.title.trim()
      && devotion.platform === "YOUTUBE"
      && isApprovedVideoUrl(devotion.url))
    .map((devotion) => ({
      devotionId: devotion.devotionId.trim(),
      title: devotion.title.trim(),
      url: new URL(devotion.url).href,
      platform: "YOUTUBE"
    }));
}

export function isApprovedVideoUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:"
      && APPROVED_VIDEO_HOSTS.has(url.hostname.toLowerCase())
      && !url.username
      && !url.password;
  } catch {
    return false;
  }
}

export async function fetchChurches(areaCode, {
  apiBaseUrl = "",
  path = CHURCHES_PATH,
  fetchImpl = globalThis.fetch
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("Church directory is unavailable.");
  }
  const query = new URLSearchParams({ areaCode: areaCode.trim() });
  const response = await fetchImpl(`${normalizeApiBase(apiBaseUrl)}${path}?${query}`, {
    method: "GET",
    headers: { Accept: "application/json" },
    credentials: "omit",
    referrerPolicy: "no-referrer"
  });
  if (!response.ok) {
    throw new DiscoveryError(response.status);
  }
  return projectPublicChurches(await response.json());
}

export async function fetchDevotions({
  apiBaseUrl = "",
  path = DEVOTIONS_PATH,
  fetchImpl = globalThis.fetch
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new Error("Devotions are unavailable.");
  }
  const response = await fetchImpl(`${normalizeApiBase(apiBaseUrl)}${path}`, {
    method: "GET",
    headers: { Accept: "application/json" },
    credentials: "omit",
    referrerPolicy: "no-referrer"
  });
  if (!response.ok) {
    throw new DiscoveryError(response.status);
  }
  return projectPublishedDevotions(await response.json());
}

export class DiscoveryError extends Error {
  constructor(status) {
    super("Discovery content could not be loaded.");
    this.name = "DiscoveryError";
    this.status = status;
  }
}

function normalizeApiBase(value) {
  return value.trim().replace(/\/+$/, "");
}

function safeHttpsUrl(value) {
  if (typeof value !== "string" || !value.trim()) {
    return "";
  }
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !url.username && !url.password ? url.href : "";
  } catch {
    return "";
  }
}

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  globalThis.crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function setFieldError(input, errorElement, message) {
  errorElement.textContent = message;
  input.setAttribute("aria-invalid", message ? "true" : "false");
}

function initializeInterestForm() {
  const form = document.querySelector("#interest-signup");
  if (!form) {
    return;
  }

  const status = document.querySelector("#form-status");
  const button = document.querySelector("#submit-button");
  const buttonLabel = button.querySelector(".button-label");
  const name = form.elements.namedItem("name");
  const email = form.elements.namedItem("email");
  const areaCode = form.elements.namedItem("areaCode");
  const consent = form.elements.namedItem("consent");
  const campaignId = getCampaignId(globalThis.location?.search ?? "");
  let previousPayload = "";
  let idempotencyKey = "";

  const clearFieldErrors = () => {
    setFieldError(name, document.querySelector("#name-error"), "");
    setFieldError(email, document.querySelector("#email-error"), "");
    setFieldError(areaCode, document.querySelector("#area-error"), "");
    document.querySelector("#consent-error").textContent = "";
    consent.setAttribute("aria-invalid", "false");
  };

  for (const input of [name, email, areaCode]) {
    input.addEventListener("input", () => {
      if (input.getAttribute("aria-invalid") === "true") {
        input.setAttribute("aria-invalid", "false");
        const error = document.querySelector(`#${input.id}-error`);
        error.textContent = "";
      }
    });
  }
  consent.addEventListener("change", () => {
    consent.setAttribute("aria-invalid", "false");
    document.querySelector("#consent-error").textContent = "";
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    status.hidden = true;
    clearFieldErrors();
    const values = {
      name: name.value,
      email: email.value,
      areaCode: areaCode.value,
      phone: form.elements.namedItem("phone").value,
      consent: consent.checked,
      followUpConsent: form.elements.namedItem("followUpConsent").checked
    };
    const validationMessage = validateInterestInput(values);
    if (validationMessage) {
      const invalid = !values.name.trim() ? name
        : !values.email.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(values.email.trim()) ? email
          : !values.areaCode.trim() ? areaCode : consent;
      const errorId = invalid.id === "consent" ? "consent-error" : `${invalid.id}-error`;
      document.querySelector(`#${errorId}`).textContent = validationMessage;
      invalid.setAttribute("aria-invalid", "true");
      invalid.focus();
      return;
    }

    const comparablePayload = JSON.stringify(values);
    if (comparablePayload !== previousPayload) {
      previousPayload = comparablePayload;
      idempotencyKey = newIdempotencyKey();
    }

    const payload = buildInterestPayload(values, idempotencyKey, campaignId);
    button.disabled = true;
    button.classList.add("is-loading");
    buttonLabel.textContent = "Sending…";
    try {
      await submitInterest(payload, {
        apiBaseUrl: globalThis.SAVED2SERVE_CONFIG?.apiBaseUrl ?? ""
      });
      form.reset();
      clearFieldErrors();
      previousPayload = "";
      idempotencyKey = "";
      showStatus(status, "success", "Thanks for reaching out. Your interest has been received.");
    } catch {
      const message = error instanceof RegistrationError
        ? error.message
        : "We couldn't connect just now. Your details are still here—please check your connection and try again.";
      showStatus(status, "error", message);
    } finally {
      button.disabled = false;
      button.classList.remove("is-loading");
      buttonLabel.textContent = "Send my interest";
    }
  });
}

function initializeCampaignQr() {
  const image = document.querySelector("#campaign-qr");
  const download = document.querySelector("#campaign-qr-download");
  const destinationText = document.querySelector("#campaign-qr-destination");
  if (!image || !download || !destinationText) {
    return;
  }

  try {
    const destination = buildCampaignLandingUrl(globalThis.location.href);
    const qrDataUrl = createCampaignQrDataUrl(destination);
    image.src = qrDataUrl;
    download.href = qrDataUrl;
    destinationText.textContent = destination;
  } catch (error) {
    const message = document.querySelector("#campaign-qr-error");
    message.textContent = "The QR code is unavailable here. You can still share the public site link.";
    message.hidden = false;
    download.hidden = true;
    image.hidden = true;
  }
}

function showStatus(element, type, message) {
  element.className = `form-status ${type}`;
  element.textContent = message;
  element.hidden = false;
  element.focus();
}

function setDiscoveryState(container, state, message) {
  container.replaceChildren();
  container.className = `discovery-results discovery-${state}`;
  container.setAttribute("aria-busy", state === "loading" ? "true" : "false");
  const text = document.createElement("p");
  text.className = "discovery-placeholder";
  text.textContent = message;
  container.append(text);
}

function appendRetry(container, onRetry) {
  const retry = document.createElement("button");
  retry.type = "button";
  retry.className = "text-button";
  retry.textContent = "Try again";
  retry.addEventListener("click", onRetry);
  container.append(retry);
}

function renderChurches(container, churches) {
  container.replaceChildren();
  container.className = `discovery-results ${churches.length ? "discovery-loaded" : "discovery-empty"}`;
  container.setAttribute("aria-busy", "false");
  if (churches.length === 0) {
    setDiscoveryState(container, "empty", "No approved churches are listed for that exact area yet.");
    return;
  }
  const cards = document.createElement("div");
  cards.className = "church-cards";
  for (const church of churches) {
    const card = document.createElement("article");
    card.className = "church-card";
    const name = document.createElement("h3");
    name.textContent = church.displayName;
    card.append(name);
    if (church.publicAddress) {
      const address = document.createElement("p");
      address.className = "church-address";
      address.textContent = church.publicAddress;
      card.append(address);
    }
    if (church.publicWebsite) {
      const website = document.createElement("a");
      website.href = church.publicWebsite;
      website.target = "_blank";
      website.rel = "noopener noreferrer";
      website.textContent = "Visit website";
      website.setAttribute("aria-label", `Visit ${church.displayName} website (opens in a new tab)`);
      card.append(website);
    }
    cards.append(card);
  }
  container.append(cards);
}

function renderDevotions(container, devotions) {
  container.replaceChildren();
  container.className = `devotion-results ${devotions.length ? "discovery-loaded" : "discovery-empty"}`;
  container.setAttribute("aria-busy", "false");
  if (devotions.length === 0) {
    setDiscoveryState(container, "empty", "There are no published devotion links to show just yet.");
    return;
  }
  const cards = document.createElement("div");
  cards.className = "devotion-cards";
  for (const devotion of devotions) {
    const card = document.createElement("article");
    card.className = "devotion-card";
    const label = document.createElement("span");
    label.className = "devotion-label";
    label.textContent = "WEEKLY DEVOTION";
    const title = document.createElement("h3");
    title.textContent = devotion.title;
    const link = document.createElement("a");
    link.href = devotion.url;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = "Watch on YouTube";
    link.setAttribute("aria-label", `${devotion.title} (opens in a new tab)`);
    card.append(label, title, link);
    cards.append(card);
  }
  container.append(cards);
}

function initializeChurchDiscovery() {
  const form = document.querySelector("#church-search");
  const input = document.querySelector("#church-area");
  const error = document.querySelector("#church-area-error");
  const results = document.querySelector("#church-results");
  if (!form || !input || !results) {
    return;
  }
  let activeController = null;
  const search = async (areaCode) => {
    if (activeController) {
      activeController.abort();
    }
    activeController = new AbortController();
    setDiscoveryState(results, "loading", "Searching approved service-area results…");
    try {
      const churches = await fetchChurches(areaCode, {
        apiBaseUrl: globalThis.SAVED2SERVE_CONFIG?.apiBaseUrl ?? "",
        path: globalThis.SAVED2SERVE_CONFIG?.churchesPath ?? CHURCHES_PATH,
        fetchImpl: (url, options) => fetch(url, { ...options, signal: activeController.signal })
      });
      renderChurches(results, churches);
    } catch (failure) {
      if (failure.name === "AbortError") {
        return;
      }
      setDiscoveryState(results, "error", "Church results are temporarily unavailable. Please try again.");
      appendRetry(results, () => search(areaCode));
    }
  };
  input.addEventListener("input", () => {
    input.setAttribute("aria-invalid", "false");
    error.textContent = "";
  });
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const areaCode = input.value.trim();
    if (!areaCode) {
      input.setAttribute("aria-invalid", "true");
      error.textContent = "Enter an area or postcode to search.";
      input.focus();
      return;
    }
    input.setAttribute("aria-invalid", "false");
    error.textContent = "";
    search(areaCode);
  });
}

async function initializeDevotionDiscovery() {
  const results = document.querySelector("#devotion-results");
  if (!results) {
    return;
  }
  const load = async () => {
    setDiscoveryState(results, "loading", "Loading published devotions…");
    try {
      const devotions = await fetchDevotions({
        apiBaseUrl: globalThis.SAVED2SERVE_CONFIG?.apiBaseUrl ?? "",
        path: globalThis.SAVED2SERVE_CONFIG?.devotionsPath ?? DEVOTIONS_PATH
      });
      renderDevotions(results, devotions);
    } catch {
      setDiscoveryState(results, "error", "Weekly devotions are temporarily unavailable. Please try again.");
      appendRetry(results, load);
    }
  };
  await load();
}

if (typeof document !== "undefined") {
  initializeCampaignQr();
  initializeInterestForm();
  initializeChurchDiscovery();
  initializeDevotionDiscovery();
}
