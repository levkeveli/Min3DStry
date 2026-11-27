# v160.8 parity finish checklist

Use the [software capabilities](../port-capabilities.json) and [mechanics ledger](../mechanics-parity.json) as the source of truth for completion. The order below prioritizes work that unlocks several ledger entries. Links point to existing work so contributors can continue it without opening duplicate issues or PRs.

## Software closeout order

| Priority | Work | Evidence needed |
| --- | --- | --- |
| 1 | Campaign world size and persistence ([#538](https://github.com/levkeveli/Min3DStry/issues/538), [#723](https://github.com/levkeveli/Min3DStry/pull/723)) | Run the original 600×600 campaign sectors through load, play, save, and reload in the unified New 3DS emulator build. Record peak memory by phase and reopen the emitted saves with stock Java v160.8. |
| 2 | Multiplayer production and session lifecycle ([#703](https://github.com/levkeveli/Min3DStry/issues/703), [#713](https://github.com/levkeveli/Min3DStry/issues/713), [#683](https://github.com/levkeveli/Min3DStry/issues/683), [#708](https://github.com/levkeveli/Min3DStry/issues/708)) | Compare production and world-state checkpoints against a stock PC server. Replay discovery, saved server selection, and reconnect. Verify a stock Java client can discover, join, play, and reconnect to a 3DS-hosted session. |
| 3 | Remaining stock simulation and configuration | Compare payload transport, factories, reconstructors, Erekir heat and neoplasm, and processor editing against the same stock maps and inputs. Verify interactive block configuration and persist the outcomes through stock save readback. Continue [#685](https://github.com/levkeveli/Min3DStry/issues/685) and recheck the logistics, production, combat, and logic rows against current main. |
| 4 | Campaign and client presentation ([#721](https://github.com/levkeveli/Min3DStry/issues/721), [#709](https://github.com/levkeveli/Min3DStry/issues/709), [#705](https://github.com/levkeveli/Min3DStry/issues/705), [#717](https://github.com/levkeveli/Min3DStry/issues/717), [#753](https://github.com/levkeveli/Min3DStry/issues/753)) | Replay the solar system and research tree, stock building bars, schematic sharing, darkness and map bounds in the relevant contexts. Recheck outstanding ledger entries for minimap, controller/touch menus, stock settings, mods, and rendering using screenshot or pixel evidence where appropriate. |

For each item, record the pinned upstream version, port commit, scenario and inputs, comparable checkpoints, and artifact or log paths. Use exact stock-versus-port comparisons for deterministic behavior. For controls and rendering, verify the relevant interaction or draw result in the unified ROM. Update the affected ledger rows and regenerate both gauges in the same PR when the evidence supports promotion. Use [#691](https://github.com/levkeveli/Min3DStry/pull/691) to coordinate reusable stock-versus-port measurements.

When integration is blocked, check the full build and ARM11 smoke results for the current `main` commit under the [automation policy](../development/automation-policy.md). For older PRs, compare their acceptance criteria with current `main` before rebasing or rerunning them. Carry forward only work that is still missing.

## New 3DS hardware acceptance

The [hardware test queue](../hardware-test-queue.md) tracks the physical runs separately. Stage the unified ROM from a verified `main` commit, record its SHA-256, and collect evidence for gameplay and build UI, campaign saves, audible stock sound and music, PC cross-play, and sustained load at the 24 FPS target. Keep the workload and memory measurements linked to [#678](https://github.com/levkeveli/Min3DStry/issues/678). Promote each hardware check when its own evidence is recorded.
