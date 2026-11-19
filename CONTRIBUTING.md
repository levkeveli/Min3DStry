# Contributing to Min3DStry

This is an experimental native homebrew project. The target is full Mindustry support on both 3DS generations, but **no implementation is assumed complete until tested**.

## For humans

Choose a Ready GitHub Issue, or submit a proposal. Check existing owners, blockers and PRs first. Keep PRs focused; link issues, attach actual tests, update docs and disclose unsupported models or functionality. Human maintainers are Levy, Kevin and Elia.

## For coding agents

Read `AGENTS.md` and `docs/development/automation-policy.md`. Claim a task, use an isolated branch, implement/test/document, create a PR and let CI plus trusted auto-merge policy handle the normal path. Never bypass risk gates, forge hardware evidence, or falsely close an issue.

## Test evidence labels

- `not-tested`
- `host-tested`
- `emulator-tested`
- `old3ds-hardware-tested`
- `new3ds-hardware-tested`
- `upstream-parity-tested`
- `multiplayer-tested`

These are evidence descriptors, not claims that all target features are supported.

## Initial workflow

1. Pick `status:ready` and verify dependencies.
2. Claim the issue following the documented lease process.
3. Develop in `agent/<issue>-<slug>` or `feat/<issue>-<slug>`.
4. Open PR with issue reference, scope, test evidence, documentation change and risk class.
5. Let checks and review automation run; fix failures.
6. Eligible routine PRs merge automatically after policy checks. Escalation-required PRs wait for human decision.

The public GitHub Pages site reflects **merged, validated** project state, not promises or PR drafts.
