#!/usr/bin/env python3
"""Apply or release PR holds based on verification of the current main HEAD."""

import json
import os
import subprocess

from pr_automation import (
    MAIN_VERIFY_BLOCKED,
    MAIN_VERIFY_FIX,
    TIER_C_LABELS,
    is_main_verification_fix,
    main_verification_status,
)


def gh(repo: str, *args: str) -> str:
    return subprocess.run(
        ["gh", *args, "--repo", repo], check=True, text=True, stdout=subprocess.PIPE
    ).stdout


def recheck_failed_policy_gates(repo: str, prs: list[dict]) -> None:
    for pr in prs:
        labels = {label["name"].lower() for label in pr["labels"]}
        if pr["isDraft"] or labels & (TIER_C_LABELS - {MAIN_VERIFY_BLOCKED}):
            continue
        if not any(
            check.get("name") == "PR policy gate" and check.get("conclusion") == "FAILURE"
            for check in pr["statusCheckRollup"]
        ):
            continue
        runs = json.loads(gh(
            repo, "run", "list", "--workflow", "ai-pr-review.yml", "--event",
            "pull_request", "--commit", pr["headRefOid"], "--branch", pr["headRefName"],
            "--limit", "1", "--json", "databaseId,conclusion",
        ))
        if not runs or runs[0]["conclusion"] != "failure":
            continue
        run_id = str(runs[0]["databaseId"])
        jobs = json.loads(gh(repo, "run", "view", run_id, "--json", "jobs"))["jobs"]
        if any(job["name"] == "PR policy gate" and job["conclusion"] == "failure" for job in jobs):
            gh(repo, "run", "rerun", run_id, "--failed")


def main() -> None:
    repo = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    sha, blockers, missing_workflows = main_verification_status(repo, token)
    for label, description in (
        (MAIN_VERIFY_BLOCKED, "Main post-merge verification hold"),
        (MAIN_VERIFY_FIX, "PR that repairs or reverts a main verification failure"),
    ):
        gh(repo, "label", "create", label, "--force", "--color", "d73a4a",
           "--description", description)

    prs = json.loads(gh(repo, "pr", "list", "--base", "main", "--state", "open",
                        "--limit", "100", "--json",
                        "number,labels,title,body,isDraft,headRefOid,headRefName,statusCheckRollup"))
    issues = json.loads(gh(repo, "issue", "list", "--state", "open", "--label",
                           MAIN_VERIFY_BLOCKED, "--json", "number"))
    if blockers and not issues:
        gh(repo, "issue", "create", "--title", f"Main verification blocked at {sha[:12]}",
           "--label", MAIN_VERIFY_BLOCKED, "--body",
           f"Main verification at `{sha}` is blocked.\n\n"
           + "\n".join(f"- {reason}" for reason in blockers)
           + f"\n\nFix or revert the failure via a PR carrying `{MAIN_VERIFY_FIX}`.")

    for pr in prs:
        number = str(pr["number"])
        labels = {label["name"].lower() for label in pr["labels"]}
        if blockers:
            if MAIN_VERIFY_BLOCKED not in labels and not is_main_verification_fix(
                labels, pr["title"], pr["body"] or ""
            ):
                gh(repo, "pr", "edit", number, "--add-label", MAIN_VERIFY_BLOCKED)
        else:
            for label in sorted(labels & {MAIN_VERIFY_BLOCKED, MAIN_VERIFY_FIX}):
                gh(repo, "pr", "edit", number, "--remove-label", label)

    if blockers:
        # A merge performed with GITHUB_TOKEN can produce no push-triggered runs.
        for filename in missing_workflows:
            gh(repo, "workflow", "run", filename, "--ref", "main")
    else:
        # Label edits made with GITHUB_TOKEN do not enqueue pull_request runs.
        recheck_failed_policy_gates(repo, prs)
        for issue in issues:
            gh(repo, "issue", "close", str(issue["number"]), "--comment",
               f"Both heavy workflows passed on main at `{sha}`. Released PR holds.")
    print(f"Main verification at {sha}: {', '.join(blockers) or 'green'}")


if __name__ == "__main__":
    main()
