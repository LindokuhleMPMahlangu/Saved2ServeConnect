#!/usr/bin/env python3
import json
import hashlib
import logging
import os
import re
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 16 * 1024
CHURCH_RESULT_LIMIT = 20
GOOGLE_PLACES_URL = "https://places.googleapis.com/v1/places:searchText"
ALLOWED_FIELDS = {
    "idempotencyKey",
    "name",
    "email",
    "areaCode",
    "phone",
    "consent",
    "source",
    "followUpConsent",
}
EMAIL_PATTERN = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")
ALLOWED_SOURCES = {"landing-page", "campaign-qr:community-outreach"}
LOGGER = logging.getLogger("saved2serve.demo")
GOOGLE_REQUEST_LOCK = threading.Lock()
LAST_GOOGLE_REQUEST = 0.0


class DemoIdempotencyConflict(Exception):
    pass


def validate_registration(payload):
    if not isinstance(payload, dict) or set(payload) - ALLOWED_FIELDS:
        return False

    text_limits = {
        "idempotencyKey": 128,
        "name": 100,
        "email": 254,
        "areaCode": 24,
        "source": 64,
    }
    for field, limit in text_limits.items():
        value = payload.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > limit:
            return False
    if not EMAIL_PATTERN.fullmatch(payload["email"].strip()):
        return False
    if payload["source"] not in ALLOWED_SOURCES:
        return False
    if payload.get("phone") is not None and (
        not isinstance(payload["phone"], str) or len(payload["phone"]) > 40
    ):
        return False
    if payload.get("consent") is not True:
        return False
    if "followUpConsent" in payload and not isinstance(payload["followUpConsent"], bool):
        return False
    return True


def get_demo_table():
    import boto3

    table_name = os.environ.get("SAVED2SERVE_DEMO_TABLE_NAME", "").strip()
    region = os.environ.get("AWS_REGION", "eu-north-1").strip()
    if not table_name:
        raise RuntimeError("The demo DynamoDB table name is not configured.")
    return boto3.resource("dynamodb", region_name=region).Table(table_name)


def save_registration(payload, table=None):
    table = table or get_demo_table()
    registration_id = "DEMO#" + hashlib.sha256(payload["idempotencyKey"].encode("utf-8")).hexdigest()
    received_at = datetime.now(timezone.utc).isoformat()
    persisted_fields = {
        "name": payload["name"].strip(),
        "email": payload["email"].strip(),
        "phone": (payload.get("phone") or "").strip() or None,
        "areaCode": payload["areaCode"].strip(),
        "source": payload["source"],
        "followUpConsent": payload.get("followUpConsent", False),
        "consent": True,
    }
    fingerprint = hashlib.sha256(
        json.dumps(persisted_fields, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    item = {
        "registrationId": registration_id,
        "receivedAt": received_at,
        "consentedAt": received_at,
        "requestFingerprint": fingerprint,
        **persisted_fields,
    }
    try:
        table.put_item(
            Item=item,
            ConditionExpression="attribute_not_exists(registrationId)",
        )
        return registration_id
    except Exception as error:
        error_code = getattr(error, "response", {}).get("Error", {}).get("Code")
        if error_code != "ConditionalCheckFailedException":
            raise

    existing = table.get_item(
        Key={"registrationId": registration_id},
        ConsistentRead=True,
    ).get("Item")
    if existing and existing.get("requestFingerprint") == fingerprint:
        return registration_id
    raise DemoIdempotencyConflict


class ChurchLookupError(Exception):
    pass


def _fetch_map_json(url, data=None, headers=None):
    request_headers = {
        "Accept": "application/json",
        "User-Agent": "Saved2ServeConnect/0.1 (public church lookup demo)",
    }
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(
        url,
        data=data,
        headers=request_headers,
        method="POST" if data is not None else "GET",
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def _google_maps_url(place_id):
    return "https://www.google.com/maps/search/?api=1&query_place_id=" + urllib.parse.quote(
        place_id, safe=""
    )


def _query_churches(area):
    global LAST_GOOGLE_REQUEST

    api_key = os.environ.get("GOOGLE_MAPS_API_KEY", "").strip()
    if not api_key:
        raise ChurchLookupError("Google Maps API key is not configured.")

    request_body = {
        "textQuery": f"churches near {area}, Johannesburg, South Africa",
        "includedType": "church",
        "strictTypeFiltering": True,
        "maxResultCount": CHURCH_RESULT_LIMIT,
        "languageCode": "en",
        "regionCode": "ZA",
    }
    data = json.dumps(request_body).encode("utf-8")
    with GOOGLE_REQUEST_LOCK:
        delay = 1.0 - (time.monotonic() - LAST_GOOGLE_REQUEST)
        if delay > 0:
            time.sleep(delay)
        try:
            response = _fetch_map_json(
                GOOGLE_PLACES_URL,
                data,
                {
                    "Content-Type": "application/json",
                    "X-Goog-Api-Key": api_key,
                    "X-Goog-FieldMask": "places.id,places.displayName,places.formattedAddress",
                },
            )
        except urllib.error.HTTPError as error:
            LOGGER.error("DEMO_CHURCH_LOOKUP_FAILED provider=google_places status=%d", error.code)
            raise ChurchLookupError("Google Maps search is unavailable.") from error
        except (urllib.error.URLError, TimeoutError) as error:
            LOGGER.error(
                "DEMO_CHURCH_LOOKUP_FAILED provider=google_places type=%s",
                type(error).__name__,
            )
            raise ChurchLookupError("Google Maps search is unavailable.") from error
        LAST_GOOGLE_REQUEST = time.monotonic()
    if not isinstance(response, dict) or not isinstance(response.get("places", []), list):
        raise ChurchLookupError("Google Maps returned an invalid church list.")

    churches = []
    for place in response.get("places", []):
        if not isinstance(place, dict):
            continue
        place_id = place.get("id")
        display_name = place.get("displayName")
        if (not isinstance(place_id, str) or not place_id
                or not isinstance(display_name, dict)
                or not isinstance(display_name.get("text"), str)):
            continue
        address = place.get("formattedAddress")
        name = display_name["text"].strip()
        if not name:
            continue
        churches.append({
            "churchId": f"google:{place_id}",
            "displayName": name[:160],
            "publicWebsite": "",
            "publicAddress": address.strip()[:240] if isinstance(address, str) else "",
            "mapUrl": _google_maps_url(place_id),
        })
    return churches


def find_churches(area):
    if not isinstance(area, str):
        raise ValueError("An area or postcode is required.")
    area = area.strip()
    if (not area or len(area) > 24
            or any(not (character.isalnum() or character in " -'.") for character in area)):
        raise ValueError("Enter an area name or postcode using letters and numbers.")
    return _query_churches(area)


class DemoInterestHandler(BaseHTTPRequestHandler):
    def send_json(self, status, response):
        body = json.dumps(response).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/healthz":
            self.send_json(200, {"status": "ok", "mode": "demo-dynamodb"})
            return
        parsed_path = urllib.parse.urlsplit(self.path)
        if parsed_path.path == "/v1/churches":
            query = urllib.parse.parse_qs(parsed_path.query, keep_blank_values=True)
            if set(query) != {"areaCode"} or len(query["areaCode"]) != 1:
                self.send_json(400, {"error": "An areaCode query parameter is required."})
                return
            try:
                churches = find_churches(query["areaCode"][0])
            except ValueError:
                self.send_json(400, {"error": "Enter a valid area or postcode."})
                return
            except Exception as error:
                LOGGER.error("DEMO_CHURCH_LOOKUP_FAILED type=%s", type(error).__name__)
                self.send_json(503, {"error": "The map search service is unavailable."})
                return
            self.send_json(200, {"churches": churches, "source": "Google Maps"})
            return
        self.send_json(404, {"error": "Not found."})

    def do_POST(self):
        if self.path != "/v1/interests":
            self.send_json(404, {"error": "Not found."})
            return
        if self.headers.get_content_type() != "application/json":
            self.send_json(415, {"error": "JSON content type is required."})
            return
        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_json(400, {"error": "Request is invalid."})
            return
        if content_length <= 0 or content_length > MAX_BODY_BYTES:
            self.send_json(413, {"error": "Request is too large or empty."})
            return
        try:
            payload = json.loads(self.rfile.read(content_length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            self.send_json(400, {"error": "Request is invalid."})
            return
        if not validate_registration(payload):
            self.send_json(400, {"error": "Please review the required registration fields."})
            return

        try:
            registration_id = save_registration(payload)
        except DemoIdempotencyConflict:
            self.send_json(409, {"error": "This demo request key was already used with different details."})
            return
        except Exception as error:
            error_code = getattr(error, "response", {}).get("Error", {}).get("Code")
            LOGGER.error(
                "DEMO_DYNAMODB_WRITE_FAILED code=%s",
                error_code or type(error).__name__,
            )
            self.send_json(503, {"error": "The demo storage service is unavailable."})
            return

        self.send_json(
            201,
            {
                "mode": "demo",
                "saved": True,
                "registrationId": registration_id,
                "message": "Demo only: fictional registration saved to DynamoDB.",
            },
        )

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8081), DemoInterestHandler).serve_forever()
