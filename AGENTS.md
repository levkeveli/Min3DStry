# AGENTS.md — Min3DStry

## Mission

**Min3DStry is a 1:1 port of upstream Mindustry to New Nintendo 3DS/2DS — NOT a simplified clone, demake, fan recreation, or custom mini-game.** Adapt and run the pinned upstream Mindustry v8 build `160.8` code and content ([upstream port plan](docs/upstream-port.md)), preserving 1:1 gameplay rules, complete content catalog (blocks, items, liquids, units, bullets, status effects, planets, sectors, tech tree, logic), original sprites/audio assets, `.msav` maps/saves, and cross-platform multiplayer with the unmodified Java PC v160.8 release. Complete functional and content parity before performance optimization, then verify at least 24 FPS on physical New Nintendo 3DS/2DS within its application-memory budget. New Nintendo 3DS/2DS is the sole supported release and verification target. Preserve older Original-model test records as historical evidence. Never assert an untested feature works.

### 1:1 Port Constraints (Mandatory for AI Agents)

1. **Adapt real pinned upstream Mindustry v160.8 code and content** (see `docs/upstream-port.md` and `research/upstream-runtime/README.md`); preserve upstream IDs, serialized behavior, maps, saves, and network protocol.
2. **Do NOT build simplified clones or fake subsets** (for example, 4-block custom prototypes, invented mechanics, or custom non-upstream file/network formats).
3. **Existing C demo code (`src/render/scene_2d.c`, `src/render/factory_demo.c`, `src/sim/parity_scenario.c`) is a 3DS hardware/platform verification harness ONLY** — do not expand it into a bespoke from-scratch Mindustry reimplementation.
4. **Modify upstream code where needed for 3DS compatibility & performance, while staying technically original Mindustry:** You may adapt upstream Mindustry/Arc code for TeaVM/ARM11 compatibility (e.g., reflection, `Unsafe`, executors), 3DS memory/frame-time budgets, and 3DS platform services (PICA200/Citro2D/Citro3D renderer, dual-screen UI/controls, NDSP audio, `libctru` OS/FS/networking/threading/memory) — provided gameplay rules, content catalog/IDs, `.msav` maps/saves, and cross-platform multiplayer with unmodified Java PC v160.8 remain 1:1 compatible.

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
7. Run independent verification yourself—including `./dev.sh test`, `./dev.sh build`, and `./dev.sh emu-test` (inspecting Azahar screenshots, RPC perf stats, and `svcOutputDebugString` telemetry in New 3DS mode) instead of asking humans to run or screenshot emulator builds. Let CI and AI review evaluate the PR; do not wait for human approvals.
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
- No invented progress percentages, benchmark numbers, release status or test reports. Whenever a capability changes verification stage, update `docs/port-capabilities.json` with verifiable evidence paths and regenerate `docs/progress-gauge.svg` (`python3 tools/port_progress.py --write-svg`).

## Integration and quality

- `main` is always protected, buildable and the only source for public implemented-state claims. Fix or revert a merge that breaks post-merge verification. Accepted cost of the two-lane model: a PR that breaks the 3DS/ARM11 build can land on main and is caught by post-merge verification (hold until fixed or reverted) rather than blocked pre-merge.
- Required PR CI covers the fast lane (host build/tests, lint, docs, policy gate, AI review); heavy 3DS/ARM11 build and smoke workflows run on PRs as non-required signals and on every push to `main`, where the PR policy gate holds ordinary PRs until both heavy workflows pass on the current HEAD. The alert manages the `main-verify-blocked` label and retries failed policy gates when verification passes. Never bypass, disable or fake checks. See `docs/development/automation-policy.md` for the two-lane model.
- Maintain the pinned upstream game simulation rules on New 3DS/2DS. Preserve cross-platform network behavior; differences require explicit review and evidence.
- Do not introduce proprietary Nintendo code/firmware or undistributable third-party assets.
- Follow upstream Mindustry GPLv3 obligations and audit dependencies.
- Check the actual repository before making architectural assumptions; active upstream v160.8 ARM11 source bring-up lives in `research/upstream-runtime/` (TeaVM C + `libctru`).

## Risk gates

All same-repo PRs are reviewed by `AI review` and auto-merge once required CI checks pass. Human maintainers never need to review PRs unless an explicit maintainer hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`) is attached, main verification is blocked (the fixing or reverting PR carries `main-verify-fix` and mentions “revert” or “Main verification failed” in its title or body), or an agent explicitly escalates a licensing/IP, security/secrets, or destructive release decision.

Read `docs/development/automation-policy.md` for merge rules and escalation semantics.
