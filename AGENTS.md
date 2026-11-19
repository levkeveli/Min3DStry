# AGENTS.md — Mindustry 3DS

## Mission

Build a **native, open-source full Mindustry port** for Original 3DS/2DS and New 3DS/2DS, sharing one source tree and preserving game logic, maps, saves and cross-platform multiplayer when technically viable. Optimize hardware-specific rendering, input, audio, scheduling and memory without changing deterministic gameplay. Use the actual PICA200 GPU. Never assert an untested feature works.

## Humans and AI agents

- Maintainers: **Levy, Kevin, Elia**; roles are flexible, no exclusive subsystem silos.
- AI coding agents have broad autonomy inside assigned issue/task scope: design, implement, refactor, run tools, create tests, spawn subtasks when supported, open PRs, repair CI and perform agent-to-agent review.
- Agents may create and claim Ready issues under the task-leasing protocol. Humans can override assignments and priorities.
- **Routine eligible PRs auto-merge**; do not require humans to click Merge. Escalate only architectural, legal, security, destructive, release or risky compatibility decisions, as defined in automation policy.

## Issue/task protocol

1. Read this file, architecture docs and available project board.
2. Pick or accept a Ready issue with clear outcome, acceptance tests, dependency and ownership metadata.
3. Atomically claim/lease the issue; set human sponsor, agent identity, branch and expiry/heartbeat. Recheck claim before changes. If leasing automation is unavailable, ask a maintainer to assign issues instead of racing.
4. Use one dedicated branch/worktree per task. Never work in another agent's working tree or force-push their branch.
5. Keep changes reviewable and modular. Coordinate shared interfaces via an issue/comment before changing them.
6. Add tests, diagnostic evidence and docs in the same PR. Link the issue (`Closes #N`).
7. Let independent review/check automation evaluate the PR. Address failures and conflicts. Never self-attest that code review is independent.
8. Enable auto-merge only after the merge policy marks the PR eligible. An agent may request automatic merge but must not override blocked checks.
9. After merge, verify relevant post-merge CI, Pages deployment and board/status updates. Create follow-up issues for outstanding work.

## Development speed

- Prefer automated repeatable commands over manual chores. Maintain `./dev.sh doctor|build|test|run|device|package|status` where supported; unimplemented commands must fail clearly.
- Preserve a host-platform build/test path for shared game logic; keep 3DS-specific code behind interfaces.
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

Agents can merge **routine** scoped changes via trusted automation after checks pass. Pause for human decision on: licensing/IP uncertainty; secrets/permissions; changes to CI trust, auto-merge policy, branch protections, agent credentials or `AGENTS.md`; non-recoverable data changes; material architecture changes; releases marked stable; protocol/gameplay-breaking decisions; or an explicit maintainer hold.

Read `docs/development/automation-policy.md` for precise merge classification, claim leasing and escalation semantics.
