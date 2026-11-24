#!/usr/bin/env python3
"""Triage open PRs: auto-rerun failed heavy checks once per head SHA, draft idle PRs.

Runs on a schedule (see pr-triage.yml). For every open PR targeting main:
- Rerun failed heavy pull_request runs for its current head SHA once. The
  attempted SHA is recorded in a comment marker so each new head gets
  exactly one automatic retry.
- If the PR has had no activity for 7 days, convert it to draft via the
  convertPullRequestToDraft GraphQL mutation and post an explanatory comment.
"""
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta, timezone

from pr_automation import FAILURE_CONCLUSIONS, HEAVY_VERIFY_WORKFLOWS

REPO = os.environ.get("GITHUB_REPOSITORY", "levkeveli/Min3DStry")
NOW = datetime.now(timezone.utc)
RERUN_MARKER = "<!-- auto-rerun:"
DRAFT_DAYS = 7


def gh_api(*args, check=True):
    return subprocess.run(
        ["gh", "api", *args], capture_output=True, text=True, check=check
    )


def get_comments(n):
    endpoint = f"repos/{REPO}/issues/{n}/comments?per_page=100"
    result = gh_api(endpoint, "--paginate", "--slurp", "--jq", "flatten", check=False)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "unknown flag: --slurp" not in result.stderr:
        raise RuntimeError(result.stderr.strip())
    # Older gh versions can emit a JSON object on each line across all pages.
    result = gh_api(endpoint, "--paginate", "--jq", ".[] | @json", check=False)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip())
    return [json.loads(line) for line in result.stdout.splitlines()]


def rerun_failed(n, head) -> bool:
    """Returns True if auto-reruns were attempted in this pass."""
    result = gh_api(
        f"repos/{REPO}/actions/runs?head_sha={head}&event=pull_request&per_page=100",
        "--paginate", "--jq", ".workflow_runs[] | @json",
    )
    runs = [json.loads(line) for line in result.stdout.splitlines()]
    runs = [
        run for run in runs
        if run["conclusion"] in FAILURE_CONCLUSIONS
        and run["name"] in HEAVY_VERIFY_WORKFLOWS.values()
    ]
    if not runs:
        return False
    comments = get_comments(n)
    if any(RERUN_MARKER + head in (c.get("body") or "") for c in comments):
        return False
    # Reserve the retry before issuing it so a partial failure cannot repeat it.
    gh_api(
        f"repos/{REPO}/issues/{n}/comments", "-X", "POST",
        "-f", f"body={RERUN_MARKER}{head} --> auto-rerun requested for this head.",
    )
    for run in runs:
        rr = subprocess.run(
            ["gh", "run", "rerun", str(run["id"]), "--failed", "--repo", REPO],
            capture_output=True, text=True,
        )
        if rr.returncode != 0:
            print(f"PR #{n}: auto-rerun of run {run['id']} failed: {rr.stderr.strip()}")
    print(f"PR #{n}: auto-rerun attempted for {len(runs)} failed run(s)")
    return True


def draft_if_idle(n, draft, updated):
    if draft or NOW - updated <= timedelta(days=DRAFT_DAYS):
        return
    node_id = gh_api(f"repos/{REPO}/pulls/{n}", "--jq", ".node_id").stdout.strip()
    mutation = (
        "mutation($id:ID!){convertPullRequestToDraft(input:{pullRequestId:$id})"
        "{pullRequest{isDraft}}}"
    )
    r = subprocess.run(
        ["gh", "api", "graphql", "-f", f"query={mutation}", "-F", f"id={node_id}"],
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


def main():
    prs = json.loads(
        gh_api(
            f"repos/{REPO}/pulls?base=main&state=open&per_page=100",
            "--jq",
            "[.[] | {number, draft, updated_at, head: .head.sha}]",
        ).stdout
    )
    for pr in prs:
        try:
            updated = datetime.fromisoformat(pr["updated_at"].replace("Z", "+00:00"))
            reran = rerun_failed(pr["number"], pr["head"])
            # A PR that just got an auto-rerun comment is active; do not draft it.
            if not reran:
                draft_if_idle(pr["number"], pr["draft"], updated)
        except Exception as e:
            print(f"PR #{pr['number']}: triage error, continuing: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
