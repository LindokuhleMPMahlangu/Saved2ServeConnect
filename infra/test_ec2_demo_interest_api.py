import http.client
import json
import threading
import unittest
from http.server import ThreadingHTTPServer

from infra.ec2_demo_interest_api import DemoInterestHandler, validate_registration


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
        self.payload = {
            "idempotencyKey": "attempt-123",
            "name": "Fictional Example",
            "email": "fictional@example.org",
            "areaCode": "1800",
            "consent": True,
            "source": "landing-page",
            "followUpConsent": False,
        }

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

    def test_post_returns_demo_only_non_persistent_result(self):
        status, cache_control, body = self.request("POST", "/v1/interests", self.payload)
        self.assertEqual(status, 200)
        self.assertEqual(cache_control, "no-store")
        self.assertFalse(json.loads(body)["saved"])

    def test_post_rejects_missing_consent(self):
        status, _, _ = self.request(
            "POST", "/v1/interests", {**self.payload, "consent": False}
        )
        self.assertEqual(status, 400)

    def test_health_endpoint_reports_demo_mode(self):
        status, _, body = self.request("GET", "/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"status": "ok", "mode": "demo"})

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


if __name__ == "__main__":
    unittest.main()
