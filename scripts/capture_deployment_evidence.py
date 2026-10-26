#!/usr/bin/env python3
"""Write a minimal, privacy-safe deployment evidence record."""

import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

SAFE_FIELDS = {
    "GITHUB_REPOSITORY",
    "GITHUB_RUN_ID",
    "GITHUB_RUN_ATTEMPT",
    "GITHUB_RUN_URL",
    "GITHUB_SHA",
    "SAM_STACK_NAME",
    "AWS_REGION",
    "CHANGE_SET_ARN",
    "STACK_STATUS",
    "STACK_LAST_UPDATED_AT",
    "SMOKE_TEST_OUTCOME",
}
ALLOWED_OUTCOMES = {"success", "failure", "cancelled", "skipped"}


def capture_evidence(environment=None, recorded_at=None):
    environment = os.environ if environment is None else environment
    missing = sorted(field for field in SAFE_FIELDS if not environment.get(field))
    if missing:
        raise ValueError(f"Required deployment evidence fields are missing: {', '.join(missing)}.")

    commit = environment["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise ValueError("GITHUB_SHA must be a full commit SHA.")
    if not environment["GITHUB_RUN_ID"].isdigit() or not environment["GITHUB_RUN_ATTEMPT"].isdigit():
        raise ValueError("GitHub run identifiers must be numeric.")
    if environment["SMOKE_TEST_OUTCOME"] not in ALLOWED_OUTCOMES:
        raise ValueError("SMOKE_TEST_OUTCOME is not a recognized step result.")

    return {
        "recordedAt": (recorded_at or datetime.now(timezone.utc)).isoformat(),
        "repository": environment["GITHUB_REPOSITORY"],
        "commit": commit.lower(),
        "workflowRunId": environment["GITHUB_RUN_ID"],
        "workflowRunAttempt": environment["GITHUB_RUN_ATTEMPT"],
        "workflowRunUrl": environment["GITHUB_RUN_URL"],
        "stackName": environment["SAM_STACK_NAME"],
        "region": environment["AWS_REGION"],
        "changeSetArn": environment["CHANGE_SET_ARN"],
        "stackStatus": environment["STACK_STATUS"],
        "stackLastUpdatedAt": environment["STACK_LAST_UPDATED_AT"],
        "smokeTestOutcome": environment["SMOKE_TEST_OUTCOME"],
    }


def main():
    try:
        evidence = capture_evidence()
        output_path = Path("deployment-evidence") / "evidence.json"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_path:
            with open(summary_path, "a", encoding="utf-8") as summary:
                summary.write("## Deployment evidence\n\n")
                summary.write(f"- Repository: `{evidence['repository']}`\n")
                summary.write(f"- Commit: `{evidence['commit']}`\n")
                summary.write(f"- Workflow run: `{evidence['workflowRunUrl']}`\n")
                summary.write(f"- Stack: `{evidence['stackName']}` ({evidence['stackStatus']})\n")
                summary.write(f"- Region: `{evidence['region']}`\n")
                summary.write(f"- Change set: `{evidence['changeSetArn']}`\n")
                summary.write(f"- API smoke test: `{evidence['smokeTestOutcome']}`\n")
                summary.write(f"- Evidence artifact file: `{output_path.as_posix()}`\n")
    except (OSError, ValueError) as failure:
        print(f"Unable to capture deployment evidence: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
