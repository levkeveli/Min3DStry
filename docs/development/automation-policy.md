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
3. Required checks (`Bootstrap checks`, `PR policy gate`, `AI review`) pass on the head commit.
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
- **Full 3DS build check:** `.github/workflows/full-build.yml` builds the default app in the pinned devkitARM image when sources, headers, the Makefile, sprite fixtures or the atlas tool change.
- **External fork PRs:** Fork PRs do not receive repository secrets or auto-merge and require a maintainer review before merge.
- **Self-hosted Linux CI runners (`wings02`):** Same-repo PRs, `main` pushes, and `workflow_dispatch` runs route Linux jobs across 7 persistent self-hosted runners (`[self-hosted, linux, x64, min3dstry]`) on `wings02` using an asymmetric NUMA split (4 runners pinned to Node 0 with 4 memory channels and local PCIe SSD/NIC affinity; 3 runners pinned to Node 1 with 3 memory channels). Each runner maintains an isolated `GRADLE_USER_HOME` and passes `M3DS_DOCKER_CPUSET_CPUS`/`M3DS_DOCKER_CPUSET_MEMS` into pinned `devkitpro/devkitarm` Docker builds. External fork PRs automatically fall back to GitHub-hosted `ubuntu-latest` and never execute on persistent self-hosted runners or access secrets; `Windows host tests` remains on `windows-latest` and `Pages` stays on `ubuntu-latest`. Workflow-level `concurrency:` cancels superseded PR runs, and ARM11 smoke jobs are scoped as described below.
- **Self-service task claiming:** Agents do not wait for human assignment. To claim a `status:ready` issue from [Min3DStry Roadmap](https://github.com/orgs/levkeveli/projects/1), check that no open PR or recent claim comment exists, comment on the issue with the agent handle and dedicated branch name, swap `status:ready` -> `status:in-progress` (and `status:in-review` once the PR is open), reference `Closes #N` in the PR body, and let CI + `AI review` auto-merge.

## Priority Bootstrap runner

When repository variable `M3DS_PRIORITY_BOOTSTRAP` is `true`, same-repository
Bootstrap checks route to `min3dstry-priority` on `levyathan`. This
dedicated runner uses the workstation’s eight performance cores and runs under
an isolated service account. Heavy compiler jobs retain the `wings02` pool,
and fork PRs retain GitHub-hosted Bootstrap checks.

Unset the variable before taking the workstation runner offline. New
Bootstrap jobs will then use `min3dstry-fast` on `wings02`. GitHub queues
jobs until a runner with the requested label is available.

## Live runner status

The Pages dashboard reads the public `runner-status:runners.json` feed every 75
seconds, with a public CDN fallback. It shows GitHub Actions self-hosted runners, their GitHub-reported
availability, and links to matching active jobs. If GitHub reports a runner
busy before exposing the job assignment, the page shows the busy state with
job details unavailable.

On `wings01`, `min3ds-runner-status.timer` runs
`tools/publish_runner_status.py` once a minute. The publisher uses the server's
authenticated GitHub CLI and publishes runner names and public Actions job
metadata only. It writes when status changes or after five minutes without an
update. The dashboard flags a snapshot older than seven minutes. Check the
timer and `journalctl -u min3ds-runner-status.service` if the feed is stale.
To inspect a snapshot without publishing, run
`python3 tools/publish_runner_status.py --print` with an authenticated GitHub
CLI that can read the repository runner inventory.

## ARM11 smoke scope

The lightweight scope job selects baselines from the changed paths on PRs and
main pushes. Live audio changes select audio and network checks. Live graphics
and live client changes select network and offline single-player checks.
Runtime Markdown updates do not trigger the heavy build workflows by
themselves. Shared TeaVM inputs, unknown runtime paths, CI workflow edits,
and unavailable change lists still select the full suite. A run with no
classified paths only skips the full suite if every changed file is Markdown. Use
`workflow_dispatch` to run the full suite on demand.

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
