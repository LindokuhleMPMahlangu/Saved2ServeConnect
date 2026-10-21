const API_PATH = "/v1/interests";

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

export function buildInterestPayload(values, idempotencyKey) {
  const payload = {
    idempotencyKey,
    name: values.name.trim(),
    email: values.email.trim(),
    areaCode: values.areaCode.trim(),
    consent: values.consent,
    source: "landing-page",
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

    const payload = buildInterestPayload(values, idempotencyKey);
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
    } catch (error) {
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

function showStatus(element, type, message) {
  element.className = `form-status ${type}`;
  element.textContent = message;
  element.hidden = false;
  element.focus();
}

if (typeof document !== "undefined") {
  initializeInterestForm();
}
