import io
import json
import os
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

import smoke_test_api


class FakeResponse:
    def __init__(self, status, body):
        self.status = status
        self.code = status
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self, limit):
        return self.body[:limit]


class FakeOpener:
    def __init__(self, responses, requests):
        self.responses = iter(responses)
        self.requests = requests

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


class SmokeTestApiTests(unittest.TestCase):
    def test_runs_public_auth_boundary_and_authenticated_read_checks(self):
        responses = [
            FakeResponse(200, b'{"devotions":[]}'),
            FakeResponse(200, b'{"churches":[]}'),
            FakeResponse(401, b'{"error":"unauthorized"}'),
            FakeResponse(200, b'{"items":[],"nextCursor":null}'),
        ]
        requests = []
        opener = FakeOpener(responses, requests)
        with patch.object(smoke_test_api.urllib.request, "build_opener", return_value=opener):
            checks = smoke_test_api.run_smoke_tests(
                "https://api.example.org/prod", "short-lived-token"
            )

        self.assertEqual(
            [
                "public devotions",
                "public church directory",
                "leader route rejects unauthenticated request",
                "authenticated leader engagement queue",
            ],
            [name for name, _ in checks],
        )
        self.assertEqual(4, len(requests))
        self.assertEqual("GET", requests[0][0].get_method())
        self.assertIsNone(requests[0][0].get_header("Authorization"))
        self.assertEqual("GET", requests[2][0].get_method())
        self.assertIsNone(requests[2][0].get_header("Authorization"))
        self.assertEqual("Bearer short-lived-token", requests[3][0].get_header("Authorization"))
        self.assertIn("areaCode=1800", requests[1][0].full_url)
        self.assertEqual("https://api.example.org/prod/v1/devotions", requests[0][0].full_url)
        self.assertTrue(all(timeout == smoke_test_api.TIMEOUT_SECONDS for _, timeout in requests))

    def test_rejects_http_base_url(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            smoke_test_api.validated_base_url("http://api.example.org")

    def test_rejects_wrong_public_collection_shape(self):
        with self.assertRaisesRegex(RuntimeError, "devotions"):
            smoke_test_api.require_collection({"items": []}, "devotions")

    def test_rejects_personal_fields_in_public_collection(self):
        with self.assertRaisesRegex(RuntimeError, "unexpected field"):
            smoke_test_api.require_collection(
                {"churches": [{"churchId": "public", "email": "private@example.org"}]},
                "churches",
            )

    def test_failure_output_does_not_include_token(self):
        output = io.StringIO()
        error = io.StringIO()
        with patch.dict(
            os.environ,
            {
                "API_BASE_URL": "https://api.example.org",
                "SMOKE_TEST_LEADER_ACCESS_TOKEN": "do-not-print-this",
            },
        ), patch.object(
            smoke_test_api,
            "run_smoke_tests",
            side_effect=RuntimeError("Authenticated leader queue smoke check returned HTTP 401."),
        ), redirect_stdout(output), redirect_stderr(error):
            result = smoke_test_api.main()

        self.assertEqual(1, result)
        self.assertNotIn("do-not-print-this", output.getvalue() + error.getvalue())
        self.assertIn("HTTP 401", error.getvalue())


if __name__ == "__main__":
    unittest.main()
