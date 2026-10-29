#!/usr/bin/env python3
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 16 * 1024
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
            self.send_json(200, {"status": "ok", "mode": "demo"})
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

        self.send_json(
            200,
            {
                "mode": "demo",
                "saved": False,
                "message": "Demo only: request validated and discarded; no information was saved.",
            },
        )

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8081), DemoInterestHandler).serve_forever()
