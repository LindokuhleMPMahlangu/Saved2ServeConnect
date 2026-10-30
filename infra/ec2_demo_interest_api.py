#!/usr/bin/env python3
import json
import re
import hashlib
import logging
import os
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 16 * 1024
CHURCH_LOOKUP_CACHE_SECONDS = 3600
CHURCH_LOOKUP_CACHE_LIMIT = 128
CHURCH_SEARCH_RADIUS_METERS = 5000
CHURCH_RESULT_LIMIT = 40
NOMINATIM_URL = os.environ.get(
    "SAVED2SERVE_NOMINATIM_URL",
    "https://nominatim.openstreetmap.org/search",
)
OVERPASS_URL = os.environ.get(
    "SAVED2SERVE_OVERPASS_URL",
    "https://overpass-api.de/api/interpreter",
)
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
CHURCH_LOOKUP_LOCK = threading.RLock()
CHURCH_LOOKUP_CACHE = {}
LAST_NOMINATIM_REQUEST = 0.0


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


def _fetch_map_json(url, data=None):
    request = urllib.request.Request(
        url,
        data=data,
        headers={
            "Accept": "application/json",
            "User-Agent": "Saved2ServeConnect/0.1 (public church lookup demo)",
        },
        method="POST" if data is not None else "GET",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())


def _find_area_location(area):
    global LAST_NOMINATIM_REQUEST

    params = {
        "format": "jsonv2",
        "limit": "1",
        "countrycodes": "za",
    }
    if re.fullmatch(r"\d{4}", area):
        params["postalcode"] = area
        params["country"] = "South Africa"
    else:
        params["q"] = f"{area}, Johannesburg, South Africa"

    with CHURCH_LOOKUP_LOCK:
        delay = 1.0 - (time.monotonic() - LAST_NOMINATIM_REQUEST)
        if delay > 0:
            time.sleep(delay)
        results = _fetch_map_json(f"{NOMINATIM_URL}?{urllib.parse.urlencode(params)}")
        LAST_NOMINATIM_REQUEST = time.monotonic()

    if not isinstance(results, list) or not results:
        return None
    try:
        latitude = float(results[0]["lat"])
        longitude = float(results[0]["lon"])
    except (KeyError, TypeError, ValueError):
        raise ChurchLookupError("Map service returned an invalid location.")
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise ChurchLookupError("Map service returned an invalid location.")
    return latitude, longitude


def _church_address(tags):
    if tags.get("addr:full"):
        return tags["addr:full"].strip()[:240]
    parts = [
        " ".join(part for part in (tags.get("addr:housenumber"), tags.get("addr:street")) if part),
        tags.get("addr:suburb") or tags.get("addr:city"),
        tags.get("addr:postcode"),
    ]
    return ", ".join(part.strip() for part in parts if part and part.strip())[:240]


def _https_website(tags):
    value = (tags.get("website") or tags.get("contact:website") or "").strip()
    if not value:
        return ""
    if not value.startswith(("https://", "http://")):
        value = "https://" + value
    try:
        parsed = urllib.parse.urlsplit(value)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            return ""
        return value[:500]
    except ValueError:
        return ""


def _query_churches(latitude, longitude):
    query = (
        "[out:json][timeout:15];"
        "("
        f'nwr(around:{CHURCH_SEARCH_RADIUS_METERS},{latitude:.6f},{longitude:.6f})'
        '["amenity"="place_of_worship"]["religion"="christian"];'
        ");out center tags 60;"
    )
    data = urllib.parse.urlencode({"data": query}).encode("utf-8")
    response = _fetch_map_json(OVERPASS_URL, data)
    if not isinstance(response, dict) or not isinstance(response.get("elements"), list):
        raise ChurchLookupError("Map service returned an invalid church list.")

    churches = []
    for element in response["elements"]:
        if not isinstance(element, dict) or not isinstance(element.get("tags"), dict):
            continue
        tags = element["tags"]
        name = tags.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        element_type = element.get("type")
        element_id = element.get("id")
        if element_type not in {"node", "way", "relation"} or not isinstance(element_id, int):
            continue
        churches.append({
            "churchId": f"osm:{element_type}/{element_id}",
            "displayName": name.strip()[:160],
            "publicWebsite": _https_website(tags),
            "publicAddress": _church_address(tags),
            "mapUrl": f"https://www.openstreetmap.org/{element_type}/{element_id}",
        })
        if len(churches) == CHURCH_RESULT_LIMIT:
            break
    return churches


def find_churches(area):
    if not isinstance(area, str):
        raise ValueError("An area or postcode is required.")
    area = area.strip()
    if (not area or len(area) > 24
            or any(not (character.isalnum() or character in " -'.") for character in area)):
        raise ValueError("Enter an area name or postcode using letters and numbers.")
    cache_key = area.casefold()
    with CHURCH_LOOKUP_LOCK:
        cached = CHURCH_LOOKUP_CACHE.get(cache_key)
        if cached and time.monotonic() - cached[0] < CHURCH_LOOKUP_CACHE_SECONDS:
            return cached[1]
        location = _find_area_location(area)
        churches = [] if location is None else _query_churches(*location)
        CHURCH_LOOKUP_CACHE[cache_key] = (time.monotonic(), churches)
        if len(CHURCH_LOOKUP_CACHE) > CHURCH_LOOKUP_CACHE_LIMIT:
            oldest_key = min(CHURCH_LOOKUP_CACHE, key=lambda key: CHURCH_LOOKUP_CACHE[key][0])
            del CHURCH_LOOKUP_CACHE[oldest_key]
        return churches


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
                LOGGER.error(
                    "DEMO_CHURCH_LOOKUP_FAILED type=%s",
                    type(error).__name__,
                )
                self.send_json(503, {"error": "The map search service is unavailable."})
                return
            self.send_json(200, {"churches": churches, "source": "OpenStreetMap"})
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
