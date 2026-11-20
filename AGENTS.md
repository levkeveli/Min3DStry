# AGENTS.md — Min3DStry

## Mission

Build a **native, open-source full Mindustry port** for Original 3DS/2DS and New 3DS/2DS, sharing one source tree and preserving game logic, maps, saves and cross-platform multiplayer when technically viable. Optimize hardware-specific rendering, input, audio, scheduling and memory without changing deterministic gameplay. Use the actual PICA200 GPU. Never assert an untested feature works.

## Humans and AI agents

- Maintainers: **admiraly, Hxgg22, Satixfied**; roles are flexible, no exclusive subsystem silos.
- AI coding agents have broad autonomy inside assigned issue/task scope: design, implement, refactor, run tools, create tests, spawn subtasks when supported, open PRs, repair CI and perform agent-to-agent review.
- Agents may create and claim Ready issues under the task-leasing protocol. Humans can override assignments and priorities.
- **Same-repo PRs auto-merge after CI and AI review**; human maintainers never need to manually review or approve PRs unless an explicit hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) is attached.

## Issue/task protocol

1. Read this file, architecture docs and available project board.
2. Pick any `status:ready` issue with clear outcome, acceptance tests, and no active PR or lease; do not wait for human assignment.
3. Self-claim the issue atomically on GitHub before coding (verify no open PR or recent claim comment exists, comment with agent/branch, and swap `status:ready` -> `status:in-progress`); avoid racing on active tasks.
4. Use one dedicated branch/worktree per task. Never work in another agent's working tree or force-push their branch.
5. Keep changes reviewable and modular. Coordinate shared interfaces via an issue/comment before changing them.
6. Add tests and docs in the same PR when changing behavior, and reference the linked issue (`Closes #N` or `#N`) when applicable.
7. Run independent verification yourself—including `./dev.sh test`, `./dev.sh build`, and `./dev.sh emu-test` (inspecting Azahar screenshots, RPC perf stats, and `svcOutputDebugString` telemetry across Old/New 3DS modes) instead of asking humans to run or screenshot emulator builds. Let CI and AI review evaluate the PR; do not wait for human approvals.
8. Auto-merge runs automatically once required checks and AI review pass unless an explicit hold label is set; humans only intervene on hold labels or physical-hardware milestones.
9. After merge, verify relevant post-merge CI, Pages deployment and board/status updates when relevant.

## Development speed

- Prefer automated repeatable commands over manual chores. Maintain `./dev.sh doctor|build|test|emu-test|run|device|package|status` where supported; unimplemented commands must fail clearly.
- Preserve a host-platform build/test path for shared game logic; keep 3DS-specific code behind interfaces.
- Use `./dev.sh emu-test` (`tools/verify_azahar.py`) to autonomously boot `.3dsx` builds in Azahar across Old and New 3DS modes, collect `svcOutputDebugString` logs + UDP RPC perf stats, and inspect captured PNG screenshots directly.
- Cache toolchains and converted assets; support incremental builds and rapid real-hardware deployment/log collection where feasible.
- Implement deterministic upstream-versus-port simulation comparisons and game/network protocol regression tests.
- Track benchmark provenance: model, firmware, build commit, test scenario, memory, FPS and frame times.

## Public documentation

- Every change to behavior, support, architecture or tooling updates corresponding docs **in the same PR**.
- Pages deploys automatically from merged `main`; show the verified deployed SHA and date, actual checks, status, open tasks and compatibility claims with evidence.
- Distinguish Planned / Experimental / Emulator-tested / Hardware-verified / Released.
- No invented progress percentages, benchmark numbers, release status or test reports.

## Integration and quality

- `main` is always protected, buildable and the only source for public implemented-state claims.
- Required CI must cover available meaningful build, test, lint, docs and security checks. Never bypass, disable or fake checks.
- Maintain identical game simulation rules between Old and New 3DS. Preserve cross-platform network behavior; differences require explicit review and evidence.
- Do not introduce proprietary Nintendo code/firmware or undistributable third-party assets.
- Follow upstream Mindustry GPLv3 obligations and audit dependencies.
- Check the actual repository before making architectural assumptions; Java/AOT portability is still a research task.

## Risk gates

All same-repo PRs are reviewed by `AI review` and auto-merge once required CI checks pass. Human maintainers never need to review PRs unless an explicit maintainer hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) is attached or an agent explicitly escalates a licensing/IP, security/secrets, or destructive release decision.

Read `docs/development/automation-policy.md` for merge rules and escalation semantics.
