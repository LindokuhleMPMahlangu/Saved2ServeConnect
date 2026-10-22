const QUEUE_PATH = "/v1/admin/engagements";
const STATUSES = new Set(["PENDING_REVIEW", "APPROVED", "REJECTED"]);

export class LeaderApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "LeaderApiError";
    this.status = status;
  }
}

export async function fetchEngagementQueue(accessToken, {
  apiBaseUrl = "",
  status = "PENDING_REVIEW",
  limit = 25,
  cursor = null,
  fetchImpl = globalThis.fetch
} = {}) {
  if (!STATUSES.has(status) || !Number.isInteger(limit) || limit < 1 || limit > 100) {
    throw new TypeError("Queue filters are invalid.");
  }
  const query = new URLSearchParams({ status, limit: String(limit) });
  if (cursor) {
    query.set("cursor", cursor);
  }
  const response = await fetchImpl(`${normalizeBase(apiBaseUrl)}${QUEUE_PATH}?${query}`, {
    method: "GET",
    headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
    credentials: "omit",
    referrerPolicy: "no-referrer",
    cache: "no-store"
  });
  if (!response.ok) {
    throw apiError(response.status, "The review queue could not be loaded.");
  }
  const body = await response.json();
  if (!body || !Array.isArray(body.items)
      || !(body.nextCursor === null || typeof body.nextCursor === "string")) {
    throw new Error("The review queue response is invalid.");
  }
  return { items: body.items.map(projectQueueItem), nextCursor: body.nextCursor };
}

export async function updateEngagementStatus(accessToken, engagementId, status, {
  apiBaseUrl = "",
  fetchImpl = globalThis.fetch
} = {}) {
  if (!isUuid(engagementId) || !["APPROVED", "REJECTED"].includes(status)) {
    throw new TypeError("Review action is invalid.");
  }
  const response = await fetchImpl(`${normalizeBase(apiBaseUrl)}${QUEUE_PATH}/${encodeURIComponent(engagementId)}`, {
    method: "PATCH",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`
    },
    body: JSON.stringify({ status }),
    credentials: "omit",
    referrerPolicy: "no-referrer",
    cache: "no-store"
  });
  if (!response.ok) {
    throw apiError(response.status, "This engagement could not be reviewed.");
  }
  const body = await response.json();
  if (body?.status !== status) {
    throw new Error("The review response is invalid.");
  }
  return body.status;
}

function projectQueueItem(item) {
  const expected = ["engagementId", "createdAt", "name", "email", "areaCode", "type", "message", "status"];
  if (!item || typeof item !== "object"
      || expected.some((key) => typeof item[key] !== "string")
      || !isUuid(item.engagementId)
      || !["PENDING_REVIEW", "APPROVED", "REJECTED"].includes(item.status)) {
    throw new Error("The review queue contains an invalid item.");
  }
  return {
    engagementId: item.engagementId,
    createdAt: item.createdAt,
    name: item.name,
    email: item.email,
    areaCode: item.areaCode,
    phone: typeof item.phone === "string" ? item.phone : "",
    type: item.type,
    message: item.message,
    status: item.status
  };
}

function isUuid(value) {
  return typeof value === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function normalizeBase(value) {
  return value.trim().replace(/\/+$/, "");
}

function apiError(status, fallback) {
  if (status === 401) {
    return new LeaderApiError(status, "Your leader session has expired. Sign in again.");
  }
  if (status === 403) {
    return new LeaderApiError(status, "Your account is not authorized for this leader action.");
  }
  if (status === 409) {
    return new LeaderApiError(status, "This item has already been reviewed. Refresh the queue to see its current status.");
  }
  if (status === 404) {
    return new LeaderApiError(status, "This engagement is no longer available.");
  }
  return new LeaderApiError(status, fallback);
}
