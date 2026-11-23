#!/usr/bin/env python3
"""Publish only public runner and job fields to the Pages status feed."""
from __future__ import annotations

import json
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

REPO = "levkeveli/Min3DStry"
STATUS_ISSUE = "issues/424"
BODY_HEADER = "Machine-managed runner status for the Min3DStry Pages dashboard."


class ApiError(RuntimeError):
    pass


def api(path, payload=None, paginate=False):
    args = ["gh", "api"]
    if paginate:
        args.append("--paginate")
    encoded = None
    if payload is not None:
        args.extend(("--method", "PATCH", "--input", "-"))
        encoded = json.dumps(payload)
    try:
        result = subprocess.run(
            [*args, f"repos/{REPO}/{path}"], input=encoded, text=True,
            capture_output=True, check=False, timeout=20,
        )
        if result.returncode:
            raise ApiError(f"{path}: {result.stderr.strip()}")
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
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as exc:
        raise ApiError(f"{path}: {exc}") from exc


def jobs_for(run):
    try:
        pages = api(
            f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs?per_page=100",
            paginate=True,
        )
    except ApiError as exc:
        print(f"Runner job details unavailable: {exc}", file=sys.stderr)
        return []
    jobs = [job for page in pages for job in page["jobs"]]
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


def needs_update(body, runners, now):
    try:
        old = json.loads(body.split("```json\n", 1)[1].split("\n```", 1)[0])
        return (old["runners"] != runners
                or now - datetime.fromisoformat(old["updated_at"]) >= timedelta(minutes=5))
    except (IndexError, KeyError, TypeError, ValueError):
        return True


def main():
    try:
        snapshot = collect()
        now = datetime.now(timezone.utc)
        if "--print" in sys.argv:
            print(json.dumps({"updated_at": now.isoformat(), **snapshot}))
            return 0
        previous = api(STATUS_ISSUE)
        if needs_update(previous.get("body") or "", snapshot["runners"], now):
            snapshot["updated_at"] = now.isoformat()
            data = json.dumps(snapshot, separators=(",", ":")).replace("`", "\\u0060")
            api(STATUS_ISSUE, {"body": f"{BODY_HEADER}\n\n```json\n{data}\n```"})
            print(f"Published status for {len(snapshot['runners'])} runners")
        return 0
    except ApiError as exc:
        print(f"Runner status was not published: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
