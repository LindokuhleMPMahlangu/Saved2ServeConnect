import {
  beginLeaderSignIn,
  buildLeaderLogoutUrl,
  clearPendingLeaderSignIn,
  completeLeaderSignIn,
  isLeaderSessionExpired
} from "./leader-auth.js";
import { fetchEngagementQueue, LeaderApiError, updateEngagementStatus } from "./leader-api.js";

const config = globalThis.SAVED2SERVE_CONFIG ?? {};
const authConfig = config.cognito ?? {};
const apiBaseUrl = config.apiBaseUrl ?? "";
const loginPanel = document.querySelector("#leader-login");
const portal = document.querySelector("#leader-portal");
const statusRegion = document.querySelector("#leader-status");
const signInButton = document.querySelector("#leader-sign-in");
const signOutButton = document.querySelector("#leader-sign-out");
const queueForm = document.querySelector("#queue-filters");
const queueStatus = document.querySelector("#queue-status");
const queueResults = document.querySelector("#queue-results");
const nextPageButton = document.querySelector("#queue-next");
const detailPanel = document.querySelector("#engagement-detail");

let session = null;
let nextCursor = null;
let selectedStatus = "PENDING_REVIEW";
let selectedItem = null;
let activeRequest = null;
let sessionExpiryTimer = null;

function setMessage(message, kind = "info") {
  statusRegion.textContent = message;
  statusRegion.className = `leader-status ${kind}`;
  statusRegion.hidden = !message;
}

function clearSensitiveView() {
  queueResults.replaceChildren();
  detailPanel.replaceChildren();
  detailPanel.hidden = true;
  queueStatus.textContent = "";
  queueStatus.setAttribute("aria-busy", "false");
  selectedItem = null;
  nextCursor = null;
  nextPageButton.hidden = true;
}

function showSignedOut(message = "") {
  session = null;
  clearPendingLeaderSignIn();
  if (sessionExpiryTimer !== null) {
    clearTimeout(sessionExpiryTimer);
    sessionExpiryTimer = null;
  }
  if (activeRequest) {
    activeRequest.abort();
    activeRequest = null;
  }
  clearSensitiveView();
  loginPanel.hidden = false;
  portal.hidden = true;
  setMessage(message, message ? "error" : "info");
}

function activateSession(authenticatedSession) {
  session = authenticatedSession;
  const delay = Math.max(0, Math.min(session.expiresAt - Date.now(), 2_147_000_000));
  sessionExpiryTimer = setTimeout(() => {
    if (isLeaderSessionExpired(session)) {
      showSignedOut("Your leader session has expired. Sign in again.");
    }
  }, delay);
  loginPanel.hidden = true;
  portal.hidden = false;
  setMessage("");
}

function requireLiveSession() {
  if (isLeaderSessionExpired(session)) {
    showSignedOut("Your leader session has expired. Sign in again.");
    return null;
  }
  return session.accessToken;
}

function renderQueue(items, append = false, hasNextPage = false) {
  if (!append) {
    queueResults.replaceChildren();
  }
  if (items.length === 0) {
    if (append) {
      return;
    }
    const empty = document.createElement("p");
    empty.className = "queue-empty";
    empty.textContent = hasNextPage
      ? "No results on this page. Load the next page to continue."
      : `No ${queueStatus.value.replaceAll("_", " ").toLowerCase()} engagements to review.`;
    queueResults.append(empty);
    return;
  }

  queueResults.querySelector(".queue-empty")?.remove();
  let list = append ? queueResults.querySelector(".engagement-list") : null;
  if (!list) {
    list = document.createElement("div");
    list.className = "engagement-list";
    queueResults.append(list);
  }
  for (const item of items) {
    const card = document.createElement("article");
    card.className = "engagement-card";
    const select = document.createElement("button");
    select.type = "button";
    select.className = "engagement-select";
    select.setAttribute("aria-label", `View ${item.type.replaceAll("_", " ").toLowerCase()} from ${item.name}`);
    const cardTop = document.createElement("span");
    cardTop.className = "engagement-card-top";
    const kind = document.createElement("span");
    kind.textContent = item.type.replaceAll("_", " ");
    const status = document.createElement("span");
    status.className = "status-pill";
    status.textContent = item.status.replaceAll("_", " ");
    cardTop.append(kind, status);
    const name = document.createElement("strong");
    name.textContent = item.name;
    const summary = document.createElement("span");
    summary.textContent = `${item.areaCode} · ${formatDate(item.createdAt)}`;
    select.append(cardTop, name, summary);
    select.addEventListener("click", () => showDetail(item));
    card.append(select);
    list.append(card);
  }
  queueResults.append(list);
}

function showDetail(item) {
  selectedItem = item;
  detailPanel.replaceChildren();
  detailPanel.hidden = false;
  const heading = document.createElement("div");
  heading.className = "detail-heading";
  const title = document.createElement("h2");
  title.textContent = item.type.replaceAll("_", " ");
  const close = document.createElement("button");
  close.type = "button";
  close.className = "icon-button";
  close.setAttribute("aria-label", "Close engagement details");
  close.textContent = "×";
  close.addEventListener("click", () => {
    selectedItem = null;
    detailPanel.hidden = true;
    detailPanel.replaceChildren();
  });
  heading.append(title, close);

  const identity = document.createElement("dl");
  identity.className = "detail-fields";
  addDetail(identity, "Name", item.name);
  addDetail(identity, "Email", item.email, "mailto:");
  addDetail(identity, "Phone", item.phone, item.phone ? "tel:" : "");
  addDetail(identity, "Area", item.areaCode);
  addDetail(identity, "Received", formatDate(item.createdAt));
  addDetail(identity, "Status", item.status.replaceAll("_", " "));

  const messageLabel = document.createElement("h3");
  messageLabel.textContent = "Submission";
  const message = document.createElement("p");
  message.className = "detail-message";
  message.textContent = item.message;
  detailPanel.append(heading, identity, messageLabel, message);

  if (item.status === "PENDING_REVIEW") {
    const actions = document.createElement("div");
    actions.className = "review-actions";
    for (const [status, label, className] of [
      ["APPROVED", "Approve", "approve-action"],
      ["REJECTED", "Reject", "reject-action"]
    ]) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `button ${className}`;
      button.textContent = label;
      button.addEventListener("click", () => reviewItem(item, status, button));
      actions.append(button);
    }
    detailPanel.append(actions);
  }
}

function addDetail(list, label, value, hrefPrefix = "") {
  const term = document.createElement("dt");
  term.textContent = label;
  const definition = document.createElement("dd");
  if (hrefPrefix && value) {
    const link = document.createElement("a");
    link.href = `${hrefPrefix}${encodeURIComponent(value)}`;
    link.textContent = value;
    definition.append(link);
  } else {
    definition.textContent = value || "Not provided";
  }
  list.append(term, definition);
}

function formatDate(value) {
  const date = new Date(value);
  return Number.isNaN(date.valueOf()) ? "Date unavailable" : new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(date);
}

async function loadQueue(cursor = null) {
  const requestSession = session;
  const accessToken = requireLiveSession();
  if (!accessToken) {
    return;
  }
  if (activeRequest) {
    activeRequest.abort();
  }
  const requestController = new AbortController();
  activeRequest = requestController;
  queueStatus.textContent = cursor ? "Loading the next page…" : "Loading the review queue…";
  queueStatus.setAttribute("aria-busy", "true");
  if (!cursor) {
    queueResults.replaceChildren();
  }
  nextPageButton.disabled = true;
  try {
    const page = await fetchEngagementQueue(accessToken, {
      apiBaseUrl,
      status: selectedStatus,
      limit: 25,
      cursor,
      fetchImpl: (url, options) => fetch(url, { ...options, signal: requestController.signal })
    });
    if (session !== requestSession) {
      return;
    }
    renderQueue(page.items, Boolean(cursor), Boolean(page.nextCursor));
    nextCursor = page.nextCursor;
    nextPageButton.hidden = !nextCursor;
    queueStatus.textContent = `${page.items.length} engagement${page.items.length === 1 ? "" : "s"} loaded.`;
    if (!cursor && selectedItem
        && !page.items.some((item) => item.engagementId === selectedItem.engagementId)) {
      detailPanel.hidden = true;
      detailPanel.replaceChildren();
      selectedItem = null;
    }
  } catch (error) {
    if (error.name === "AbortError") {
      return;
    }
    if (error instanceof LeaderApiError && error.status === 401) {
      showSignedOut(error.message);
      return;
    }
    queueStatus.textContent = "";
    queueResults.replaceChildren();
    const message = document.createElement("p");
    message.className = "queue-error";
    message.textContent = error instanceof LeaderApiError
      ? error.message
      : "The review queue could not be loaded. Try again.";
    const retry = document.createElement("button");
    retry.type = "button";
    retry.className = "text-button";
    retry.textContent = "Try again";
    retry.addEventListener("click", () => loadQueue(cursor));
    queueResults.append(message, retry);
  } finally {
    if (activeRequest === requestController) {
      queueStatus.setAttribute("aria-busy", "false");
      nextPageButton.disabled = false;
      activeRequest = null;
    }
  }
}

async function reviewItem(item, status, button) {
  const requestSession = session;
  let accessToken;
  try {
    accessToken = requireLiveSession();
  } catch {
    return;
  }
  button.disabled = true;
  setMessage("Saving review decision…");
  try {
    await updateEngagementStatus(accessToken, item.engagementId, status, { apiBaseUrl });
    if (session !== requestSession) {
      return;
    }
    selectedItem = null;
    detailPanel.replaceChildren();
    detailPanel.hidden = true;
    setMessage(`Engagement ${status.toLowerCase()}.`, "success");
    await loadQueue();
  } catch (error) {
    if (session !== requestSession) {
      return;
    }
    if (error instanceof LeaderApiError && error.status === 401) {
      showSignedOut(error.message);
      return;
    }
    setMessage(error instanceof LeaderApiError
      ? error.message
      : "The review decision could not be saved. Try again.", "error");
    button.disabled = false;
  }
}

async function initialize() {
  try {
    const callbackSession = await completeLeaderSignIn(authConfig);
    if (callbackSession) {
      activateSession(callbackSession);
      await loadQueue();
      return;
    }

    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && session && isLeaderSessionExpired(session)) {
        showSignedOut("Your leader session has expired. Sign in again.");
      }
    });
  } catch (error) {
    showSignedOut(error.message);
    return;
  }
  showSignedOut();
}

signInButton.addEventListener("click", async () => {
  signInButton.disabled = true;
  setMessage("");
  try {
    await beginLeaderSignIn(authConfig);
  } catch (error) {
    setMessage(error.message, "error");
    signInButton.disabled = false;
  }
});

signOutButton.addEventListener("click", () => {
  try {
    const logoutUrl = buildLeaderLogoutUrl(authConfig);
    showSignedOut();
    globalThis.location.assign(logoutUrl);
  } catch (error) {
    setMessage(error.message, "error");
  }
});

queueForm.addEventListener("submit", (event) => {
  event.preventDefault();
  selectedStatus = queueStatusFilter.value;
  selectedItem = null;
  detailPanel.replaceChildren();
  detailPanel.hidden = true;
  nextCursor = null;
  nextPageButton.hidden = true;
  loadQueue();
});

const queueStatusFilter = queueForm.elements.namedItem("status");
nextPageButton.addEventListener("click", () => {
  if (nextCursor) {
    loadQueue(nextCursor);
  }
});

initialize();
