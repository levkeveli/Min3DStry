# Contributing to Min3DStry

This is an experimental native homebrew project. The target is full Mindustry support on both 3DS generations, but **no implementation is assumed complete until tested**.

## For humans

Choose a Ready GitHub Issue, or submit a proposal. Check existing owners, blockers and PRs first. Keep PRs focused; link issues, attach actual tests, update docs and disclose unsupported models or functionality. Human maintainers are admiraly, Hxgg22 and Satixfied.

## For coding agents

Read `AGENTS.md` and `docs/development/automation-policy.md`. Claim a task, use an isolated branch, implement/test/document, create a PR and let CI plus trusted auto-merge policy handle the normal path. Never bypass risk gates, forge hardware evidence, or falsely close an issue.

## Run host tests

From the repository root, run `./dev.sh test` in Bash. It runs the Python checks, compiles and executes the rendering and hardware profile tests, and checks the research simulation for repeatable results across optimization levels. This command requires Git, Python 3.10 or newer, and a host C compiler. It runs without devkitARM, Docker, an emulator, or a console.

- **Linux** — use your distribution's Bash, Git, Python 3, and GCC or Clang packages.
- **Windows** — open the MSYS2 UCRT64 shell and install the same packages used by Windows CI with `pacman -S --needed git mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-python`. Git Bash also works with native Python and a host GCC such as w64devkit on `PATH`.

Run `./dev.sh doctor` to check tool detection. Set `CC` or `PYTHON` to an executable name or path to select a specific host compiler or interpreter.

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
5. Let `Bootstrap checks`, `PR policy gate`, and `AI review` run; fix any CI failures or critical/major AI review findings.
6. Eligible same-repo PRs are approved by `AI review` and merged automatically via GitHub auto-merge without human review, unless a maintainer adds an explicit hold label (`hold`, `needs-human`, `do-not-merge`, `status:blocked`, `tier:c`).

The public GitHub Pages site reflects **merged, validated** project state, not promises or PR drafts.
