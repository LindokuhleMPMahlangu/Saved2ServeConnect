import http.client
import json
import threading
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer

from infra.ec2_demo_interest_api import (
    DemoIdempotencyConflict,
    DemoInterestHandler,
    find_churches,
    save_registration,
    validate_registration,
)


class ConditionalCheckFailed(Exception):
    def __init__(self):
        self.response = {"Error": {"Code": "ConditionalCheckFailedException"}}


class FakeDynamoTable:
    def __init__(self):
        self.items = {}

    def put_item(self, Item, ConditionExpression):
        if ConditionExpression != "attribute_not_exists(registrationId)":
            raise AssertionError("Unexpected write condition.")
        if Item["registrationId"] in self.items:
            raise ConditionalCheckFailed()
        self.items[Item["registrationId"]] = Item

    def get_item(self, Key, ConsistentRead):
        if not ConsistentRead:
            raise AssertionError("Idempotency reads must be consistent.")
        return {"Item": self.items.get(Key["registrationId"])}


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
        from infra import ec2_demo_interest_api

        with ec2_demo_interest_api.GEOCODE_LOCK:
            ec2_demo_interest_api.GEOCODE_CACHE.clear()
            ec2_demo_interest_api.LAST_NOMINATIM_REQUEST = 0.0
        with ec2_demo_interest_api.GOOGLE_REQUEST_LOCK:
            ec2_demo_interest_api.LAST_GOOGLE_REQUEST = 0.0
        self.table = FakeDynamoTable()
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

    def test_post_saves_to_dynamodb_without_returning_submitted_data(self):
        registration_id = save_registration(self.payload, self.table)
        item = self.table.items[registration_id]
        self.assertEqual(item["name"], "Fictional Example")
        self.assertEqual(item["email"], "fictional@example.org")
        self.assertEqual(item["areaCode"], "1800")
        self.assertEqual(item["followUpConsent"], False)
        self.assertNotIn("idempotencyKey", item)

    def test_same_idempotency_key_and_payload_is_idempotent(self):
        first_id = save_registration(self.payload, self.table)
        retry_id = save_registration(self.payload, self.table)
        self.assertEqual(first_id, retry_id)
        self.assertEqual(len(self.table.items), 1)

    def test_reusing_idempotency_key_for_changed_payload_conflicts(self):
        save_registration(self.payload, self.table)
        changed_payload = {**self.payload, "email": "different@example.org"}
        with self.assertRaises(DemoIdempotencyConflict):
            save_registration(changed_payload, self.table)
        self.assertEqual(len(self.table.items), 1)

    def test_post_saves_and_returns_demo_registration(self):
        from unittest.mock import patch

        with patch("infra.ec2_demo_interest_api.save_registration", return_value="DEMO#abc"):
            status, cache_control, body = self.request(
                "POST", "/v1/interests", self.payload
            )
        self.assertEqual(status, 201)
        self.assertEqual(cache_control, "no-store")
        response_body = json.loads(body)
        self.assertTrue(response_body["saved"])
        self.assertEqual(response_body["registrationId"], "DEMO#abc")
        self.assertNotIn("email", response_body)

    def test_health_endpoint_reports_dynamodb_demo_mode(self):
        status, _, body = self.request("GET", "/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(
            json.loads(body), {"status": "ok", "mode": "demo-dynamodb"}
        )

    def test_church_lookup_queries_google_places_within_five_kilometers(self):
        from unittest.mock import patch

        from infra import ec2_demo_interest_api

        map_responses = [
            [{"lat": "-26.2041", "lon": "28.0473"}],
            {
                "places": [
                    {
                        "id": "place-id",
                        "displayName": {"text": "Fictional Test Church"},
                        "formattedAddress": "1 Example Road, Johannesburg",
                        "location": {"latitude": -26.204, "longitude": 28.047},
                    },
                    {
                        "id": "outside",
                        "displayName": {"text": "Outside Radius"},
                        "formattedAddress": "Far away",
                        "location": {"latitude": -26.27, "longitude": 28.047},
                    },
                ]
            },
        ]
        with patch.dict("os.environ", {"GOOGLE_MAPS_API_KEY": "test-key"}):
            with patch.object(
                ec2_demo_interest_api,
                "_fetch_map_json",
                side_effect=map_responses,
            ) as fetch:
                churches = find_churches("2001")

        self.assertEqual(churches, [{
            "churchId": "google:place-id",
            "displayName": "Fictional Test Church",
            "publicWebsite": "",
            "publicAddress": "1 Example Road, Johannesburg",
            "mapUrl": "https://www.google.com/maps/search/?api=1&query_place_id=place-id",
        }])
        self.assertEqual(fetch.call_count, 2)
        self.assertIn("postalcode=2001", fetch.call_args_list[0].args[0])
        self.assertIn("countrycodes=za", fetch.call_args_list[0].args[0])
        request_url, request_data, headers = fetch.call_args_list[1].args
        self.assertEqual(request_url, ec2_demo_interest_api.GOOGLE_PLACES_URL)
        request_body = json.loads(request_data)
        self.assertEqual(request_body["includedType"], "church")
        self.assertEqual(request_body["locationRestriction"]["circle"]["radius"], 5000)
        self.assertEqual(headers["X-Goog-Api-Key"], "test-key")
        self.assertNotIn("places.websiteUri", headers["X-Goog-FieldMask"])

    def test_google_places_results_are_not_cached(self):
        from unittest.mock import patch

        from infra import ec2_demo_interest_api

        with patch.object(
            ec2_demo_interest_api,
            "_find_area_location",
            return_value=(-26.2, 28.0),
        ), patch.object(
            ec2_demo_interest_api,
            "_query_churches",
            side_effect=[[{"churchId": "google:first"}], [{"churchId": "google:second"}]],
        ) as search:
            self.assertEqual(find_churches("2001"), [{"churchId": "google:first"}])
            self.assertEqual(find_churches("2001"), [{"churchId": "google:second"}])
        self.assertEqual(search.call_count, 2)

    def test_google_places_requires_server_side_api_key(self):
        from unittest.mock import patch

        from infra import ec2_demo_interest_api

        with patch.dict("os.environ", {"GOOGLE_MAPS_API_KEY": ""}):
            with self.assertRaises(ec2_demo_interest_api.ChurchLookupError):
                ec2_demo_interest_api._query_churches(-26.2, 28.0)

    def test_church_lookup_rejects_full_addresses_and_unbounded_queries(self):
        for area in ["", "1 Example Street, Johannesburg", "A" * 25, "2001\t2010"]:
            with self.subTest(area=area):
                with self.assertRaises(ValueError):
                    find_churches(area)

    def test_church_lookup_route_returns_map_results(self):
        from unittest.mock import patch

        with patch(
            "infra.ec2_demo_interest_api.find_churches",
            return_value=[{"churchId": "google:place-123", "displayName": "Map Church"}],
        ):
            status, _, body = self.request("GET", "/v1/churches?areaCode=2001")
        self.assertEqual(status, 200)
        self.assertEqual(
            json.loads(body),
            {
                "churches": [{"churchId": "google:place-123", "displayName": "Map Church"}],
                "source": "Google Maps",
            },
        )

    def test_church_lookup_route_requires_only_one_area_code(self):
        status, _, body = self.request("GET", "/v1/churches?areaCode=2001&extra=value")
        self.assertEqual(status, 400)
        self.assertEqual(json.loads(body)["error"], "An areaCode query parameter is required.")

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
