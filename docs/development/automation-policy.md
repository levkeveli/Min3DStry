# Automation policy

## Objective

Enable high-throughput AI-first development **without routine human PR merges**. The rules should be enforceable by GitHub Actions, repo rulesets and/or a dedicated GitHub App; a Markdown label alone must never grant merge authority.

## Task lifecycle

`Backlog → Ready → Claimed → In Progress → In Review → Done`; `Blocked` and `Needs Human` are side states. A task can be claimed only if Ready, unblocked and not actively leased. Use atomic claim operations or a single coordinator; leases have heartbeat/TTL and recover cleanly on abandoned runs. Agent identity, human sponsor, lease ID, branch and linked PR are auditable. Do not permit two simultaneous leases for a task. Do not auto-start arbitrary workloads just because an untrusted GitHub issue is opened.

## Auto-merge tiers

| Tier | Examples | Policy |
|---|---|---|
| A: routine | features, fixes, tests, docs, workflows, tooling | Reviewed and approved by `AI review`; auto-merged once required CI checks pass |
| B: coordinated | `src/platform/`, `src/net/`, `docs/architecture.md`, build files | Reviewed and approved by `AI review`; auto-merged once required CI and parity/integration checks pass |
| C: held | PRs with an explicit hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) | Auto-merge paused until the maintainer removes the hold label |

**Critical safeguard:** never put privileged auto-merge credentials or API secrets in an untrusted fork PR runner. External fork PRs do not receive `AI review` secrets or auto-merge.

## Required conditions for unattended merge

1. PR originates from a same-repo branch (not an external fork) and is not a draft; the auto-merge job skips drafts cleanly instead of failing the workflow.
2. Merge base has no conflicts.
3. The PR is up to date with `main` and the required checks (`Bootstrap checks`, `PR policy gate`, `AI review`) pass.
4. `AI review` approves the exact head commit with no unresolved critical/major bugs, security/secret leaks, license violations, or determinism/networking regressions.
5. No explicit hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) is present on the PR.
6. Auto-merge uses GitHub's normal merge path (`--squash --match-head-commit`) and never force-pushes `main`.

## Repo/GitHub setup guidance

- Protect `main`: PR-only, forbid force pushes and deletions, required status checks; **do not require human CODEOWNER or manual maintainer reviews** for same-repo PRs.
- Enable native GitHub auto-merge so `PR Automation` can queue squash merges automatically once `AI review` approves.
- Keep GitHub Actions `GITHUB_TOKEN` read-only by default; elevate individual trusted jobs only where essential. Never run fork PR code with secrets or write permissions.
- CI status checks must be named consistently and cannot be silently skipped.
- Site deployment runs after `main` updates, separately from PR tests, and publishes the verified deployed commit SHA.

## Review/incident handling

If CI fails or `AI review` flags critical/major findings, return the task to the coding agent with logs so it can push a fix. Revert regressions via a new PR; preserve audit logs and never silently delete evidence.

## Sources

- GitHub auto-merge: https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/automatically-merging-a-pull-request
- GitHub Actions security: https://docs.github.com/en/actions/reference/security/secure-use
- GitHub Pages deployment: https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages

## Current operating mode

- **Maintainers:** `@admiraly` (admin), `@Hxgg22` and `@Satixfied` (maintain).
- **Automated PR review and merge:** `.github/workflows/ai-pr-review.yml` runs `PR policy gate`, `AI review` (using `OPENCODE_GO_API_KEY` / `OPENCODE_API_KEY` with `deepseek-v4.1-flash` by default, or `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` from repository secrets), and native GitHub auto-merge for all eligible same-repo pull requests.
- **No CODEOWNERS human gate:** `.github/CODEOWNERS` is removed. Same-repo PRs do not wait on human approval; only drafts, external fork PRs, `AI review` critical/major rejections, or explicit hold labels (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) prevent auto-merge.
- **Full 3DS build check:** `.github/workflows/full-build.yml` builds the default app in the pinned devkitARM image on every push to `main` and on PRs that change its inputs.
- **External fork PRs:** Fork PRs do not receive repository secrets or auto-merge and require a maintainer review before merge.
- **Self-hosted Linux CI runners (`wings02`):** Same-repo PRs, `main` pushes, and `workflow_dispatch` runs route Linux jobs across 7 persistent self-hosted runners (`[self-hosted, linux, x64, min3dstry]`) on `wings02` using an asymmetric NUMA split (4 runners pinned to Node 0 with 4 memory channels and local PCIe SSD/NIC affinity; 3 runners pinned to Node 1 with 3 memory channels). Each runner maintains an isolated `GRADLE_USER_HOME` and passes `M3DS_DOCKER_CPUSET_CPUS`/`M3DS_DOCKER_CPUSET_MEMS` into pinned `devkitpro/devkitarm` Docker builds. External fork PRs automatically fall back to GitHub-hosted `ubuntu-latest` and never execute on persistent self-hosted runners or access secrets; `Windows host tests` remains on `windows-latest` and `Pages` stays on `ubuntu-latest`. ARM11 smoke jobs are scoped as described below. While the `wings02` runners are offline, `full-build.yml` and `upstream-arm11-smoke.yml` temporarily run their Linux jobs on `ubuntu-latest` so `main` pushes and same-repo PRs are not queued indefinitely; restore the self-hosted `runs-on` expressions when the runners return.
- **Superseded runs:** `full-build.yml` and `upstream-arm11-smoke.yml` cancel an in-progress run for the same pull request or branch when a newer one starts, so a stale commit cannot hold the queue. Manual `workflow_dispatch` runs use a per-run group and are never canceled by a push.
- **Main failure alerts:** `main-failure-alert.yml` opens or comments on a `[main] <workflow> failed` issue when `Upstream ARM11 smoke` or `3DS full build` fails on a `main` push or dispatch, with the run URL and commit. Pull-request runs and fork repositories are ignored.
- **Live building registry gate:** the unified smoke runs `runBuildingLayersJvm` against the staged Glacier map and atlas and fails if the stock draw set loses any POWER (26), DISTRIBUTION (28), LIQUID (21) or MATERIAL (52) building. The offline CTM starts at 40s, after the hosted `UPSTREAM_SP_READY` window (~32s).
- **Azahar full-build retry:** `verify_azahar.py` retries a model once only when both FPS counters are zero (`Non-positive FPS`), the observed Azahar startup race. Other telemetry, parity, audio or screenshot failures fail immediately.
- **Dependency registry retries:** `build_unified_arm11_smoke.sh` retries the Gradle configure/generate step only when the log shows HTTP `429` or `Could not resolve` artifacts, up to three attempts with backoff. Compile errors and other failures fail immediately.
- **Self-service task claiming:** Agents do not wait for human assignment. To claim a `status:ready` issue from [Min3DStry Roadmap](https://github.com/orgs/levkeveli/projects/1), check that no open PR or recent claim comment exists, comment on the issue with the agent handle and dedicated branch name, swap `status:ready` -> `status:in-progress` (and `status:in-review` once the PR is open), reference `Closes #N` in the PR body, and let CI + `AI review` auto-merge.

## Workstation CI runners

When repository variable `M3DS_PRIORITY_BOOTSTRAP` is `true`, same-repository
Bootstrap checks route to `levyathan-priority` via `min3dstry-priority`. That
runner uses CPU 0 (one performance core) and CPUs 8–19 (12 efficiency cores).
Unset the variable before taking it offline to route new Bootstrap jobs to
`min3dstry-fast` on `wings02`.

`levyathan-arm11` adds one slot to the shared `min3dstry` pool used by ARM11
builds and probes. It uses the seven other performance cores (CPUs 1–7).
The runner uses a dedicated Linux account and runs the pinned build images
with rootless Podman. Build containers inherit the runner's CPU affinity.
GitHub assigns these jobs to any available runner with the shared label.
Fork PRs continue to use GitHub-hosted runners.

The full ARM11 build and smoke workflows use the same pinned-input fetcher.
On runners with `M3DS_UPSTREAM_CACHE` configured, it stages the local pinned
Mindustry and Arc checkouts and the checksum-verified server JAR on each job.
Missing or mismatched inputs use the normal upstream downloads.

## Live runner status

The Pages dashboard reads the [runner status record](https://github.com/levkeveli/Min3DStry/issues/424)
every 150 seconds. It shows GitHub Actions self-hosted runners, their
GitHub-reported availability, and links to matching active jobs. Unavailable job
details are shown separately from runner availability.

On `wings01`, `min3ds-runner-status.timer` runs
`tools/publish_runner_status.py` about once a minute. The publisher uses the
server's authenticated GitHub CLI and updates that record in place when status
changes or after five minutes without an update. It publishes runner names and
public Actions job metadata. The dashboard flags a snapshot older than ten
minutes. Check the timer and `journalctl -u min3ds-runner-status.service` if the
feed is stale. Run `python3 tools/publish_runner_status.py --print` to inspect a
snapshot using an authenticated GitHub CLI without publishing it.

The full embedded-loader build excludes edits confined to standalone terrain,
live network, and single-player source sets that the save-rows target does
not compile. Shared world, content, Gradle and ARM11 tooling changes still
trigger the full build, as do default C app changes. The smoke workflow
continues to select its own checks for the standalone runtime.

## ARM11 smoke scope

The scope job selects checks from the changed paths on PRs and main pushes.
Shared runtime changes run three smoke jobs. The unified build stages pinned
v160.8 inputs, verifies packaged stock audio, and publishes one ROM with the
matching server JAR and atlas metadata. The offline job replays Ground Zero
and saves and reloads it. Campaign save or fixture changes also replay
Frozen Forest from its stock backup and check that the replacement preserves
that backup. The stock Java server reopens the resulting save. The multiplayer
and audio job checks a stock-server session and replays stock campaign audio. Both replay jobs download the same
ROM and use checksum-pinned Azahar 2126.2 in New 3DS mode on separate GitHub-hosted runners.

Live audio changes select the multiplayer and audio job. Live graphics and
client changes select both replay jobs. Narrow changes to standalone probes
still select their corresponding checks. Runtime Markdown updates alone
skip the workflow's heavy jobs. Shared inputs, workflow edits, and unavailable
change lists select both unified replay jobs. Use `workflow_dispatch` to
also run the full standalone matrix, including the independent JVM and
generated-C reference comparisons.

The unified New 3DS multiplayer replay holds START for five emulated seconds
after the movement inputs. It requires both the client leave marker and the
stock server disconnect after the wave and movement checks. The
[New 3DS replay evidence](https://github.com/levkeveli/Min3DStry/actions/runs/45700048301/artifacts/15063885096)
records both disconnect events after reusing the ROM from main run
`45695100973` (SHA-256
`a4c8c6d843ab3cef6b3d334de080b0ec5aa8c762160b09ba86d6e597f3c11373`).

CircleCI and Daytona can run independent builds and emulator replays on their
own compute. Keep required GitHub status contexts and record the exact source
commit for any artifact or evidence produced on another service. The scope
classifier and host suite passed in a disposable Daytona sandbox on the
initial scope PR revision `75160c6`.

## CircleCI playable offline builds

Min3DStry's optional [CircleCI project](https://app.circleci.com/pipelines/github/levkeveli/Min3DStry)
has an experimental workflow for the standalone offline ARM11 game with
pinned upstream sources, maps, and assets. It fetches the exact pipeline
commit from the public repository over HTTPS. Trigger a pipeline on the desired branch with the boolean
parameter `build_playable_arm11` set to `true`. Routine pushes leave the workflow
off. The job uses a single Linux Docker executor and uploads the playable
`min3dstry-offline.3dsx`, commit SHA, and ROM checksum under **Artifacts →
playable-arm11**. Source and port instructions are in the
[offline gameplay guide](../gameplay/offline-singleplayer.md).

The job packages the offline campaign and Custom Game target. GitHub Actions still
owns the required checks, AI review, and Pages deployment.

## Two-lane CI gating

The fast lane requires `Bootstrap checks`, `PR policy gate`, and `AI review`.
`3DS full build` and `Upstream ARM11 smoke` run on relevant PRs as non-required
checks and on every push to `main`. Strict branch protection requires PR
branches to be up to date with `main` before merging.

The policy gate and `main-verify-alert.yml` both check the latest same-repo
push or manual run for each heavy workflow on the current `main` HEAD. The
gate fails until both runs succeed. A missing, pending, cancelled or failed
run blocks ordinary PRs, as does an API error in the policy gate. Results from
older commits are ignored.

The alert checks main after either heavy workflow completes, hourly, and on
manual dispatch. While verification is blocked, it opens a tracking issue
and applies `main-verify-blocked` to open PRs targeting `main`, except for the
repair PR. Once both workflows pass on the current HEAD, it removes the
automation labels, retries failed policy gates on open non-draft PRs without
maintainer holds, and closes the tracking issue. If a push produces no run,
the alert dispatches the missing workflow on `main` to verify the current
commit. This also covers pushes generated by the workflow token.

### Repairing main

Fix or revert a regression via a PR labeled `main-verify-fix`. The title or
body must mention “revert” or “Main verification failed”. This exempts the PR
from the main verification hold, including any existing
`main-verify-blocked` label. Required checks and maintainer hold labels still
apply. The alert creates both verification labels and clears them once main
is green.

Separately, `pr-triage.yml` checks open PRs targeting `main` every six hours.
It retries failed, timed-out, or startup-failed heavy PR workflows once per
head SHA, using a comment to record the attempt. It moves PRs with more than
seven days of inactivity to draft.
