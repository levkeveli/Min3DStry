#!/usr/bin/env python3
"""Publish only public runner and job fields to the Pages status feed."""
from __future__ import annotations

import base64
import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

REPO = "levkeveli/Min3DStry"
BRANCH = "runner-status"
PATH = "runners.json"


def api(path, payload=None, paginate=False):
    args = ["gh", "api"]
    if paginate:
        args.append("--paginate")
    encoded = None
    if payload is not None:
        args.extend(("--method", "PUT", "--input", "-"))
        encoded = json.dumps(payload)
    result = subprocess.run(
        [*args, f"repos/{REPO}/{path}"], input=encoded, text=True,
        capture_output=True, check=False, timeout=20,
    )
    if result.returncode:
        raise RuntimeError(f"GitHub API failed for {path}: {result.stderr.strip()}")
    if not paginate:
        return json.loads(result.stdout)
    decoder = json.JSONDecoder()
    pages = []
    remainder = result.stdout.lstrip()
    while remainder:
        page, offset = decoder.raw_decode(remainder)
        pages.append(page)
        remainder = remainder[offset:].lstrip()
    return pages


def jobs_for(run):
    jobs = [job for page in api(
        f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100",
        paginate=True,
    ) for job in page["jobs"]]
    return [
        {
            "name": job["name"],
            "workflow": run["name"],
            "ref": run["head_branch"],
            "url": job["html_url"],
            "started_at": job["started_at"],
            "runner": job["runner_name"],
        }
        for job in jobs if job["status"] == "in_progress" and job["runner_name"]
    ]


def collect():
    # A matrix run can remain queued while a subset of its jobs is running.
    runs = [run for status in ("in_progress", "queued")
            for run in api(f"actions/runs?status={status}&per_page=20")["workflow_runs"]]
    runs = {run["id"]: run for run in runs}.values()
    with ThreadPoolExecutor(max_workers=6) as pool:
        active = [job for jobs in pool.map(jobs_for, runs) for job in jobs]
    inventory = [runner for page in api("actions/runners?per_page=100", paginate=True)
                 for runner in page["runners"]]
    assigned = {job["runner"]: {k: v for k, v in job.items() if k != "runner"}
                for job in active}
    return {
        "runners": [
            {
                "name": runner["name"],
                "state": ("offline" if runner["status"] == "offline"
                          else "busy" if runner["busy"] else "idle"),
                "priority": any(label["name"] == "min3dstry-priority"
                                for label in runner["labels"]),
                "job": assigned.get(runner["name"]) if runner["busy"] else None,
            }
            for runner in sorted(inventory, key=lambda item: item["name"])
        ],
    }


def main():
    snapshot = collect()
    if "--print" in sys.argv:
        print(json.dumps({"updated_at": datetime.now(timezone.utc).isoformat(), **snapshot}))
        return
    previous = api(f"contents/{PATH}?ref={BRANCH}")
    old = json.loads(base64.b64decode(previous["content"]))
    now = datetime.now(timezone.utc)
    if (old.get("runners") == snapshot["runners"] and old.get("updated_at")
            and now - datetime.fromisoformat(old["updated_at"]) < timedelta(minutes=5)):
        return
    snapshot["updated_at"] = now.isoformat()
    api(f"contents/{PATH}", {
        "branch": BRANCH,
        "message": "Update runner status",
        "sha": previous["sha"],
        "content": base64.b64encode(json.dumps(snapshot, separators=(",", ":")).encode()).decode(),
    })
    print(f"Published status for {len(snapshot['runners'])} runners")


if __name__ == "__main__":
    main()
