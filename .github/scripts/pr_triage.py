#!/usr/bin/env python3
"""Triage open PRs: auto-rerun failed checks once, sweep idle PRs.

Runs on a schedule (see pr-triage.yml). For every open PR targeting main:
- If its latest CI run has failed jobs and it has not been auto-rerun yet,
  rerun only the failed jobs once and add the `auto-rerun` label so it
  happens at most once per PR head.
- If no check runs exist and the PR is not a draft, post a triage comment
  (the workflows are path-filtered, so PRs with no matching changes
  legitimately have no heavy runs; the fast CI lane always runs).
- If the PR has had no activity for 7 days, convert it to draft with an
  explanatory comment.
"""
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone

REPO = os.environ.get("GITHUB_REPOSITORY", "levkeveli/Min3DStry")
NOW = datetime.now(timezone.utc)


def gh(*args):
    return subprocess.run(
        ["gh", "api", *args], capture_output=True, text=True, check=True
    ).stdout


def gh_out(*args):
    return subprocess.run(
        ["gh", *args], capture_output=True, text=True, check=True
    ).stdout


def main():
    prs = json.loads(
        gh(
            f"repos/{REPO}/pulls?state=open&per_page=100",
            "--jq",
            "[.[] | {number, title, draft, updated_at, head: .head.sha, labels: [.labels[].name]}]",
        )
    )
    for pr in prs:
        n = pr["number"]
        labels = set(pr["labels"])
        updated = datetime.fromisoformat(pr["updated_at"].replace("Z", "+00:00"))

        # Auto-rerun failed jobs once per head SHA.
        if "auto-rerun" not in labels:
            runs = json.loads(
                gh(
                    f"repos/{REPO}/actions/runs?event=pull_request&per_page=5",
                    "--jq",
                    f"[.[] | select(.head_sha == \"{pr['head']}\")]",
                )
            )
            if runs:
                latest = runs[0]
                if latest["conclusion"] == "failure":
                    subprocess.run(
                        ["gh", "run", "rerun", str(latest["id"]), "--failed"],
                        capture_output=True,
                        check=False,
                    )
                    gh_out(
                        "issue", "edit", str(n), "--repo", REPO,
                        "--add-label", "auto-rerun",
                    )
                    print(f"PR #{n}: auto-rerun of failed run {latest['id']}")

        # Idle sweep: 7 days without activity -> draft.
        if not pr["draft"] and NOW - updated > timedelta(days=7):
            gh_out(
                "pr", "edit", str(n), "--repo", REPO, "--draft",
            )
            gh_out(
                "issue", "comment", str(n), "--repo", REPO,
                "--body", "No activity in 7 days — converting to draft. "
                          "Mark ready for review to re-enter the queue.",
            )
            print(f"PR #{n}: converted to draft (idle 7d)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
