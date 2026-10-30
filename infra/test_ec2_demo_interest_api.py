import http.client
import json
import os
import sqlite3
import tempfile
import threading
import unittest
from contextlib import closing
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from infra.ec2_demo_interest_api import (
    DemoCapacityReached,
    DemoInterestHandler,
    save_registration,
    validate_registration,
)


class DemoInterestApiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), DemoInterestHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()
        cls.server.server_close()

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database_path = os.path.join(self.temp_dir.name, "records.sqlite3")
        self.db_environment = patch.dict(
            os.environ, {"SAVED2SERVE_DEMO_DB": self.database_path}
        )
        self.db_environment.start()
        self.payload = {
            "idempotencyKey": "attempt-123",
            "name": "Fictional Example",
            "email": "fictional@example.org",
            "areaCode": "1800",
            "consent": True,
            "source": "landing-page",
            "followUpConsent": False,
        }

    def tearDown(self):
        self.db_environment.stop()
        self.temp_dir.cleanup()

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port)
        encoded_body = json.dumps(body) if body is not None else None
        headers = {"Content-Type": "application/json"} if body is not None else {}
        connection.request(method, path, body=encoded_body, headers=headers)
        response = connection.getresponse()
        result = response.status, response.getheader("Cache-Control"), response.read()
        connection.close()
        return result

    def test_accepts_valid_demo_payload(self):
        self.assertTrue(validate_registration(self.payload))

    def test_post_saves_registration_without_returning_submitted_data(self):
        status, cache_control, body = self.request("POST", "/v1/interests", self.payload)
        self.assertEqual(status, 201)
        self.assertEqual(cache_control, "no-store")
        response_body = json.loads(body)
        self.assertTrue(response_body["saved"])
        self.assertIn("registrationId", response_body)
        self.assertNotIn("email", response_body)

        with closing(sqlite3.connect(self.database_path)) as connection:
            row = connection.execute(
                "SELECT name, email, area_code FROM demo_registrations"
            ).fetchone()
        self.assertEqual(row, ("Fictional Example", "fictional@example.org", "1800"))

    def test_post_rejects_missing_consent(self):
        status, _, _ = self.request(
            "POST", "/v1/interests", {**self.payload, "consent": False}
        )
        self.assertEqual(status, 400)

    def test_health_endpoint_reports_demo_mode(self):
        status, _, body = self.request("GET", "/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(
            json.loads(body), {"status": "ok", "mode": "demo-persistent"}
        )

    def test_rejects_unexpected_fields(self):
        self.assertFalse(validate_registration({**self.payload, "address": "1 Example St"}))

    def test_rejects_missing_consent(self):
        self.assertFalse(validate_registration({**self.payload, "consent": False}))

    def test_rejects_invalid_email(self):
        self.assertFalse(validate_registration({**self.payload, "email": "invalid"}))

    def test_rejects_unknown_campaign_source(self):
        self.assertFalse(validate_registration({**self.payload, "source": "unknown"}))

    def test_rejects_non_boolean_follow_up_consent(self):
        self.assertFalse(validate_registration({**self.payload, "followUpConsent": "yes"}))

    def test_persistence_limit_is_enforced(self):
        with closing(sqlite3.connect(self.database_path)) as connection:
            with connection:
                connection.execute(
                    """
                    CREATE TABLE demo_registrations (
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
                connection.executemany(
                    "INSERT INTO demo_registrations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [
                        (str(index), "now", "Example", "fictional@example.org", None,
                         "1800", "landing-page", 0, "now")
                        for index in range(100)
                    ],
                )
        with self.assertRaises(DemoCapacityReached):
            save_registration(self.payload, self.database_path)


if __name__ == "__main__":
    unittest.main()
