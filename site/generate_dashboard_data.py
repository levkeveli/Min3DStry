#!/usr/bin/env python3
"""Generate static snapshot data (site/data.json) for the Min3DStry Pages dashboard."""

from __future__ import annotations

import argparse
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO = "levkeveli/Min3DStry"


def api_get(path: str, token: str | None) -> Any:
    url = f"https://api.github.com/repos/{REPO}/{path}"
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "Min3DStry-Pages-Builder",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def normalize_issue(raw: dict[str, Any]) -> dict[str, Any]:
    labels = [lbl["name"] for lbl in raw.get("labels", [])]
    status = "backlog"
    if raw.get("state") == "closed":
        status = "done"
    else:
        for candidate in (
            "status:in-review",
            "status:in-progress",
            "status:claimed",
            "status:ready",
            "status:blocked",
            "status:backlog",
        ):
            if candidate in labels:
                status = candidate.split(":", 1)[1]
                break

    phase = next((l.split(":", 1)[1] for l in labels if l.startswith("phase:")), None)
    area = next((l.split(":", 1)[1] for l in labels if l.startswith("area:")), None)

    return {
        "number": raw["number"],
        "title": raw["title"],
        "body": raw.get("body") or "",
        "state": raw["state"],
        "status": status,
        "phase": phase,
        "area": area,
        "labels": labels,
        "assignees": [a["login"] for a in raw.get("assignees", [])],
        "milestone": (raw.get("milestone") or {}).get("title"),
        "url": raw["html_url"],
        "updated_at": raw.get("updated_at"),
        "closed_at": raw.get("closed_at"),
    }


def build_snapshot(sha: str, token: str | None) -> dict[str, Any]:
    issues_raw = api_get("issues?state=all&per_page=100", token)
    issues = [normalize_issue(i) for i in issues_raw if "pull_request" not in i]
    pulls_raw = api_get("pulls?state=all&per_page=20", token)
    pulls = [
        {
            "number": p["number"],
            "title": p["title"],
            "state": p["state"],
            "merged_at": p.get("merged_at"),
            "author": (p.get("user") or {}).get("login"),
            "url": p["html_url"],
            "updated_at": p.get("updated_at"),
        }
        for p in pulls_raw
    ]
    milestones_raw = api_get("milestones?state=all&per_page=20", token)
    milestones = [
        {
            "number": m["number"],
            "title": m["title"],
            "description": m.get("description") or "",
            "state": m["state"],
            "open_issues": m["open_issues"],
            "closed_issues": m["closed_issues"],
            "url": m["html_url"],
        }
        for m in sorted(milestones_raw, key=lambda x: x["number"])
    ]
    try:
        runs_raw = api_get("actions/runs?per_page=10", token).get("workflow_runs", [])
    except (urllib.error.URLError, urllib.error.HTTPError):
        runs_raw = []
    runs = [
        {
            "name": r.get("name"),
            "status": r.get("status"),
            "conclusion": r.get("conclusion"),
            "branch": r.get("head_branch"),
            "event": r.get("event"),
            "sha": (r.get("head_sha") or "")[:7],
            "url": r.get("html_url"),
            "created_at": r.get("created_at"),
        }
        for r in runs_raw
    ]

    return {
        "repo": REPO,
        "sha": sha,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "project_url": "https://github.com/orgs/levkeveli/projects/1",
        "issues": issues,
        "pulls": pulls,
        "milestones": milestones,
        "runs": runs,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="site/data.json")
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", "local"))
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    try:
        snapshot = build_snapshot(args.sha, token)
    except (urllib.error.URLError, urllib.error.HTTPError) as exc:
        raise SystemExit(f"Failed to fetch GitHub snapshot: {exc}")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {out_path} ({len(snapshot['issues'])} issues, {len(snapshot['pulls'])} PRs)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
