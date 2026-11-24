#!/usr/bin/env python3
"""Triage open PRs: auto-rerun failed checks once per head SHA, draft idle PRs.

Runs on a schedule (see pr-triage.yml). For every open PR targeting main:
- If the latest run for its current head SHA failed and has not been
  auto-rerun for that SHA yet, rerun only the failed jobs once. The
  attempted SHA is recorded in a comment marker so each new head gets
  exactly one automatic retry.
- If the PR has had no activity for 7 days, convert it to draft via the
  REST API and post an explanatory comment.
"""
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone

REPO = os.environ.get("GITHUB_REPOSITORY", "levkeveli/Min3DStry")
NOW = datetime.now(timezone.utc)
RERUN_MARKER = "<!-- auto-rerun:"
DRAFT_DAYS = 7


def gh_api(*args, check=True):
    return subprocess.run(
        ["gh", "api", *args], capture_output=True, text=True, check=check
    )


def main():
    prs = json.loads(
        gh_api(
            f"repos/{REPO}/pulls?state=open&per_page=100",
            "--jq",
            "[.[] | {number, draft, updated_at, head: .head.sha}]",
        ).stdout
    )
    for pr in prs:
        n, head = pr["number"], pr["head"]
        updated = datetime.fromisoformat(pr["updated_at"].replace("Z", "+00:00"))

        # Auto-rerun every failed pull_request run for this head SHA, once.
        runs = json.loads(
            gh_api(
                f"repos/{REPO}/actions/runs?head_sha={head}&per_page=20",
                "--jq",
                "[.workflow_runs[] | select(.event == \"pull_request\" and .conclusion == \"failure\")]",
            ).stdout
        )
        if runs:
            comments = json.loads(
                gh_api(
                    f"repos/{REPO}/issues/{n}/comments?per_page=100", "--jq", "."
                ).stdout
            )
            already = any(
                RERUN_MARKER + head in (c.get("body") or "") for c in comments
            )
            if not already:
                ok = True
                for run in runs:
                    rr = subprocess.run(
                        ["gh", "run", "rerun", str(run["id"]), "--failed",
                         "--repo", REPO],
                        capture_output=True, text=True,
                    )
                    if rr.returncode != 0:
                        ok = False
                        print(f"PR #{n}: auto-rerun of run {run['id']} failed: {rr.stderr.strip()}")
                if ok:
                    gh_api(
                        f"repos/{REPO}/issues/{n}/comments", "-X", "POST",
                        "-f", f"body={RERUN_MARKER}{head} --> auto-rerun of failed jobs for this head.",
                        check=False,
                    )
                    print(f"PR #{n}: auto-rerun of {len(runs)} failed run(s)")

        # Idle sweep: 7 days without activity -> draft via GraphQL mutation.
        if not pr["draft"] and NOW - updated > timedelta(days=DRAFT_DAYS):
            pr_id = json.loads(
                gh_api(
                    f"repos/{REPO}/pulls/{n}", "--jq", ".id"
                ).stdout
            )
            mutation = (
                "mutation($id:ID!){convertPullRequestToDraft(input:{pullRequestId:$id}){pullRequest{draft}}}"
            )
            r = subprocess.run(
                ["gh", "api", "graphql", "-f", f"query={mutation}", "-F", f"id={pr_id}",
                 "--repo", REPO],
                capture_output=True, text=True,
            )
            if r.returncode == 0:
                gh_api(
                    f"repos/{REPO}/issues/{n}/comments", "-X", "POST",
                    "-f", "body=No activity in 7 days — converting to draft. Mark ready for review to re-enter the queue.",
                    check=False,
                )
                print(f"PR #{n}: converted to draft (idle {DRAFT_DAYS}d)")
            else:
                print(f"PR #{n}: draft conversion failed: {r.stderr.strip()}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
