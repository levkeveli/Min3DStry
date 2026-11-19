# Automation policy

## Objective

Enable high-throughput AI-first development **without routine human PR merges**. The rules should be enforceable by GitHub Actions, repo rulesets and/or a dedicated GitHub App; a Markdown label alone must never grant merge authority.

## Task lifecycle

`Backlog → Ready → Claimed → In Progress → In Review → Done`; `Blocked` and `Needs Human` are side states. A task can be claimed only if Ready, unblocked and not actively leased. Use atomic claim operations or a single coordinator; leases have heartbeat/TTL and recover cleanly on abandoned runs. Agent identity, human sponsor, lease ID, branch and linked PR are auditable. Do not permit two simultaneous leases for a task. Do not auto-start arbitrary workloads just because an untrusted GitHub issue is opened.

## Auto-merge tiers

| Tier | Examples | Policy |
|---|---|---|
| A: routine | isolated features, fixes, tests, docs, non-sensitive tools | Auto-merge after required CI, issue/ownership checks, independent automated review where available, and explicit trust verification |
| B: coordinated | shared interfaces, larger refactors, native hardware backends, networking internals | Auto-merge if architecture contract and relevant integration checks are satisfied; otherwise escalate |
| C: restricted | workflow permissions, branch protection, secret access, automation-policy files, license change, destructive migrations, stable release, breaking simulation/protocol | Require a human maintainer decision; never unattended merge |

**Critical safeguard:** classify based on changed paths **and actual diff/behavior**, not an agent-supplied `tier:A` label. An agent cannot self-downgrade risk. A trusted controller/maintainer must manage eligibility state. Never put privileged auto-merge credentials in an untrusted PR runner.

## Required conditions for unattended merge

1. PR belongs to a trusted agent identity or authorized contributor; its linked issue is genuinely claimed and scope matches.
2. Merge base is current, no conflict, and no overlapping task lease or open conflicting PR.
3. All required checks pass on the actual merge candidate (build, tests, docs, policy, security and any relevant parity checks).
4. Independent review has run and critical findings are resolved. Independent means separate context/identity from the authoring agent, not a self-review checkbox.
5. File-path risk gating and branch policies permit the changes; no owner hold label or open security escalation.
6. Branch protection remains enforced; auto-merge uses GitHub's normal merge path and never force-pushes `main`.

For repositories without an independently trustworthy automated review mechanism, do not pretend one exists: use the deterministic checks as the routine gate for low-risk work and explicitly document the reduced assurance, or gate broader changes for human review.

## Repo/GitHub setup guidance

- Protect `main`: PR-only, forbid force pushes and deletions, required status checks; **do not require universal human approvals** if auto-merge is the chosen workflow.
- Enable native GitHub auto-merge where possible; if a merge bot is necessary, give it minimum scoped rights and make it verify the above conditions immediately before merge.
- Keep GitHub Actions `GITHUB_TOKEN` read-only by default; elevate individual trusted jobs only where essential. Never run fork PR code with write secrets or privileged `pull_request_target` checkout.
- Require separate approvals for changes to `.github/workflows/**`, merge controller, `AGENTS.md`, this file, code signing/release automation and CI trust files.
- CI status checks must be named consistently and cannot be silently skipped.
- Site deployment should run after `main` updates, separately from PR tests; report failed deployment to maintainers. Publish the last actually deployed SHA.
- Allow agents to request auto-merge through a verified controller, not by unilaterally modifying repo protections.

## Review/incident handling

If CI fails or review finds problems, return the task to the coding agent with logs and a bounded retry budget. If retries are exhausted, mark Blocked and notify the sponsor. Revert a regression via a new reviewed PR or approved rollback process; preserve audit logs. Never silently delete evidence.

## Sources

- GitHub auto-merge: https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/automatically-merging-a-pull-request
- GitHub Actions security: https://docs.github.com/en/actions/reference/security/secure-use
- GitHub Pages deployment: https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages

## Current operating mode

- **Maintainers:** Levy (`@admiraly`, admin), Kevin and Elia (`@Hxgg22`, `@Satixfied`, maintain).
- **Automated PR review and merge:** `.github/workflows/ai-pr-review.yml` runs `PR policy gate`, `AI review` (using `OPENCODE_GO_API_KEY` / `OPENCODE_API_KEY` with `deepseek-v4.1-flash` by default, or `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GEMINI_API_KEY` from repository secrets), and native GitHub auto-merge for eligible Tier A/B pull requests on repository branches.
- **No human CODEOWNER reviews:** `require_code_owner_reviews` is disabled and `.github/CODEOWNERS` assigns no human reviewers. All same-repo PRs are reviewed, approved, and auto-merged by AI once CI passes, unless a maintainer explicitly attaches a hold label (`needs-human`, `hold`, `do-not-merge`, `status:blocked`).
- **External fork PRs:** Fork PRs do not receive repository secrets or auto-merge and require a maintainer review before merge.
- **Task leasing:** Automated issue leasing is still pending; a maintainer assigns issues in [Min3DStry Roadmap](https://github.com/orgs/levkeveli/projects/1) before agents start work.
