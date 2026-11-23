import json
import subprocess
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from tools import publish_runner_status as publisher


class RunnerStatusTests(unittest.TestCase):
    @patch.object(publisher.subprocess, "run")
    def test_paginated_gh_output_and_timeout(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '{"jobs":[]}\n{"jobs":[1]}', '')
        self.assertEqual(publisher.api("actions/jobs", paginate=True), [{"jobs": []}, {"jobs": [1]}])
        run.side_effect = subprocess.TimeoutExpired("gh", 20)
        with self.assertRaisesRegex(publisher.ApiError, "actions/jobs"):
            publisher.api("actions/jobs")

    @patch.object(publisher, "api")
    def test_partial_job_failure_preserves_runner_inventory(self, api):
        runs = [
            {"id": 1, "run_attempt": 1, "name": "CI", "head_branch": "main"},
            {"id": 2, "run_attempt": 1, "name": "CI", "head_branch": "main"},
        ]

        def respond(path, **kwargs):
            if path.startswith("actions/runs?"):
                return {"workflow_runs": runs if "queued" in path else []}
            if path.startswith("actions/runs/1/"):
                raise publisher.ApiError("Run disappeared")
            if path.startswith("actions/runs/2/"):
                return [{"jobs": [
                    {"status": "in_progress", "runner_name": "fast", "name": "Bootstrap",
                     "html_url": "https://example.com/job", "started_at": None},
                ]}]
            return [{"runners": [
                {"name": "other", "status": "online", "busy": True, "labels": []},
                {"name": "fast", "status": "online", "busy": True,
                 "labels": [{"name": "min3dstry-priority"}]},
            ]}]

        api.side_effect = respond
        with patch.object(publisher.sys, "stderr"):
            fast, other = publisher.collect()["runners"]
        self.assertTrue(fast["priority"])
        self.assertEqual(fast["job"]["name"], "Bootstrap")
        self.assertEqual(other["state"], "busy")
        self.assertIsNone(other["job"])

    def test_initial_publish_changes_and_heartbeat(self):
        now = datetime.now(timezone.utc)
        runners = [{"name": "fast", "state": "idle"}]
        data = json.dumps({"runners": runners, "updated_at": now.isoformat()})
        body = f'```json\n{data}\n```'
        self.assertTrue(publisher.needs_update("Initial issue body", runners, now))
        self.assertFalse(publisher.needs_update(body, runners, now))
        self.assertTrue(publisher.needs_update(body, [], now))
        self.assertTrue(publisher.needs_update(body, runners, now + timedelta(minutes=5)))
