#!/usr/bin/env python3
import os
import json
import re
import sqlite3
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_BODY_BYTES = 16 * 1024
MAX_DEMO_RECORDS = 100
DEFAULT_DATABASE_PATH = "/var/lib/saved2serve-demo/registrations.sqlite3"
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


class DemoCapacityReached(Exception):
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


def save_registration(payload, database_path=None):
    database_path = database_path or os.environ.get(
        "SAVED2SERVE_DEMO_DB", DEFAULT_DATABASE_PATH
    )
    database_directory = os.path.dirname(database_path)
    os.makedirs(database_directory, mode=0o700, exist_ok=True)
    if os.name == "posix":
        os.chmod(database_directory, 0o700)

    registration_id = uuid.uuid4().hex
    received_at = datetime.now(timezone.utc).isoformat()
    connection = sqlite3.connect(database_path, timeout=5)
    try:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS demo_registrations (
                registration_id TEXT PRIMARY KEY,
                received_at TEXT NOT NULL,
                name TEXT NOT NULL,
                email TEXT NOT NULL,
                phone TEXT,
                area_code TEXT NOT NULL,
                source TEXT NOT NULL,
                follow_up_consent INTEGER NOT NULL,
                consented_at TEXT NOT NULL
            )
            """
        )
        connection.execute("BEGIN IMMEDIATE")
        count = connection.execute(
            "SELECT COUNT(*) FROM demo_registrations"
        ).fetchone()[0]
        if count >= MAX_DEMO_RECORDS:
            raise DemoCapacityReached
        connection.execute(
            """
            INSERT INTO demo_registrations (
                registration_id, received_at, name, email, phone, area_code,
                source, follow_up_consent, consented_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                registration_id,
                received_at,
                payload["name"].strip(),
                payload["email"].strip(),
                (payload.get("phone") or "").strip() or None,
                payload["areaCode"].strip(),
                payload["source"],
                int(payload.get("followUpConsent", False)),
                received_at,
            ),
        )
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()

    if os.name == "posix":
        os.chmod(database_path, 0o600)
    return registration_id


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
            self.send_json(200, {"status": "ok", "mode": "demo-persistent"})
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
        except DemoCapacityReached:
            self.send_json(429, {"error": "The demo registration limit has been reached."})
            return

        self.send_json(
            201,
            {
                "mode": "demo",
                "saved": True,
                "registrationId": registration_id,
                "message": "Demo only: fictional registration saved locally on this EC2 instance.",
            },
        )

    def log_message(self, _format, *_args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8081), DemoInterestHandler).serve_forever()
