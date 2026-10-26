import json
import os
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import capture_deployment_evidence


class CaptureDeploymentEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "GITHUB_REPOSITORY": "LindokuhleMPMahlangu/Saved2ServeConnect",
            "GITHUB_RUN_ID": "123456",
            "GITHUB_RUN_ATTEMPT": "2",
            "GITHUB_RUN_URL": "https://github.com/example/repo/actions/runs/123456",
            "GITHUB_SHA": "a" * 40,
            "SAM_STACK_NAME": "saved2serve-prod",
            "AWS_REGION": "eu-west-1",
            "CHANGE_SET_ARN": "arn:aws:cloudformation:eu-west-1:123456789012:changeSet/reviewed/id",
            "STACK_STATUS": "UPDATE_COMPLETE",
            "STACK_LAST_UPDATED_AT": "2026-10-26T20:00:00Z",
            "SMOKE_TEST_OUTCOME": "success",
            "SMOKE_TEST_LEADER_ACCESS_TOKEN": "must-not-be-recorded",
            "PRIVATE_SUBMISSION": "must-not-be-recorded",
        }

    def test_evidence_contains_only_approved_deployment_metadata(self):
        evidence = capture_deployment_evidence.capture_evidence(
            self.environment,
            datetime(2026, 10, 26, tzinfo=timezone.utc),
        )

        self.assertEqual("UPDATE_COMPLETE", evidence["stackStatus"])
        self.assertEqual("success", evidence["smokeTestOutcome"])
        self.assertNotIn("SMOKE_TEST_LEADER_ACCESS_TOKEN", evidence)
        self.assertNotIn("PRIVATE_SUBMISSION", evidence)
        self.assertNotIn("must-not-be-recorded", json.dumps(evidence))

    def test_rejects_missing_required_evidence(self):
        environment = dict(self.environment)
        del environment["CHANGE_SET_ARN"]

        with self.assertRaisesRegex(ValueError, "CHANGE_SET_ARN"):
            capture_deployment_evidence.capture_evidence(environment)

    def test_rejects_unexpected_outcome(self):
        environment = dict(self.environment, SMOKE_TEST_OUTCOME="timed_out")

        with self.assertRaisesRegex(ValueError, "SMOKE_TEST_OUTCOME"):
            capture_deployment_evidence.capture_evidence(environment)

    def test_cli_writes_json_evidence_and_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            with patch.dict(os.environ, self.environment, clear=True), patch.dict(
                os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}
            ):
                original_directory = Path.cwd()
                os.chdir(directory)
                try:
                    result = capture_deployment_evidence.main()
                finally:
                    os.chdir(original_directory)

            self.assertEqual(0, result)
            artifact = Path(directory) / "deployment-evidence" / "evidence.json"
            evidence = json.loads(artifact.read_text(encoding="utf-8"))
            self.assertEqual("saved2serve-prod", evidence["stackName"])
            summary_text = summary.read_text(encoding="utf-8")
            self.assertIn("Deployment evidence", summary_text)
            self.assertNotIn("must-not-be-recorded", artifact.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
