#!/usr/bin/env python3
"""Run non-mutating public and leader API smoke checks against a deployed API."""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT_SECONDS = 10
MAX_RESPONSE_BYTES = 1_048_576
AREA_CODE = "1800"
ADMIN_QUEUE_PATH = "/v1/admin/engagements"
PUBLIC_COLLECTION_FIELDS = {
    "devotions": {"devotionId", "title", "url", "platform"},
    "churches": {"churchId", "displayName", "publicWebsite", "publicAddress"},
}


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def validated_base_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("API_BASE_URL must be an HTTPS URL without embedded credentials.")
    if parsed.query or parsed.fragment:
        raise ValueError("API_BASE_URL must not include a query or fragment.")
    return value.rstrip("/")


def request_json(base_url, path, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(f"{base_url}{path}", headers=headers, method="GET")
    opener = urllib.request.build_opener(NoRedirectHandler())
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            status = response.status
            if status != 200:
                return status, None
            payload = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as response:
        return response.code, None
    except (urllib.error.URLError, TimeoutError, OSError) as failure:
        raise RuntimeError("API request failed before receiving an HTTP response.") from failure

    if len(payload) > MAX_RESPONSE_BYTES:
        raise RuntimeError("API response exceeded the smoke-test size limit.")
    try:
        document = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise RuntimeError("API returned an invalid JSON response.") from failure
    if not isinstance(document, dict):
        raise RuntimeError("API response must be a JSON object.")
    return status, document


def require_collection(document, name):
    if not isinstance(document, dict) or not isinstance(document.get(name), list):
        raise RuntimeError(f"API response is missing the expected {name} collection.")
    allowed_fields = PUBLIC_COLLECTION_FIELDS.get(name)
    if allowed_fields is not None:
        for item in document[name]:
            if not isinstance(item, dict) or not set(item).issubset(allowed_fields):
                raise RuntimeError(f"Public {name} response contains an unexpected field.")


def run_smoke_tests(base_url, leader_token):
    checks = []

    status, document = request_json(base_url, "/v1/devotions")
    if status != 200:
        raise RuntimeError(f"Public devotions smoke check returned HTTP {status}.")
    require_collection(document, "devotions")
    checks.append(("public devotions", status))

    path = f"/v1/churches?{urllib.parse.urlencode({'areaCode': AREA_CODE})}"
    status, document = request_json(base_url, path)
    if status != 200:
        raise RuntimeError(f"Public church directory smoke check returned HTTP {status}.")
    require_collection(document, "churches")
    checks.append(("public church directory", status))

    status, _ = request_json(base_url, ADMIN_QUEUE_PATH)
    if status not in (401, 403):
        raise RuntimeError(
            f"Unauthenticated leader route should return HTTP 401 or 403, got HTTP {status}."
        )
    checks.append(("leader route rejects unauthenticated request", status))

    status, document = request_json(base_url, ADMIN_QUEUE_PATH, leader_token)
    if status != 200:
        raise RuntimeError(
            f"Authenticated leader queue smoke check returned HTTP {status}; "
            "verify the token is current and has the leader.read scope and Leaders group."
        )
    require_collection(document, "items")
    checks.append(("authenticated leader engagement queue", status))
    return checks


def main():
    try:
        base_url = validated_base_url(os.environ.get("API_BASE_URL", ""))
        leader_token = os.environ.get("SMOKE_TEST_LEADER_ACCESS_TOKEN", "")
        if not leader_token.strip():
            raise ValueError("SMOKE_TEST_LEADER_ACCESS_TOKEN is required.")
        checks = run_smoke_tests(base_url, leader_token.strip())
    except (RuntimeError, ValueError) as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1

    for name, status in checks:
        print(f"PASS: {name} (HTTP {status})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
