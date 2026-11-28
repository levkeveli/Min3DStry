# Mindustry v160.8 gameplay comparison

The [successful full smoke run](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140)
tested commit `99c7fd6b`. The table records selected outcomes from the pinned
upstream simulation running on the JVM and from the same probes transpiled to
host C by TeaVM. Each row matched at both host C optimization levels
(`-O0` and `-O2`, with `-Os` instead of `-O2` for Ground Zero).
These are deterministic simulation checkpoints. The test does not report
desktop FPS, console FPS, or the fraction of all game behavior covered.

| Scenario | Checkpoints | Outcome in both the JVM and host C |
| --- | ---: | --- |
| [Factory logistics](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140/job/139151258292) | 2 | Conveyor arrival at tick 29. Core copper totals of 201 after 240 factory updates and 203 after 720 drill updates. |
| [Production and liquids](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140/job/139151258274) | 3 | No pods when starved for 60 ticks. First pod at tick 199 in the disconnect scenario. Storage filled at tick 2254 and held for 120 ticks. |
| [Combat](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140/job/139151258017) | 2 | First delivery, shot, hit and destruction at ticks 29, 49, 62 and 382. Wave state advanced to wave 2 with one Gamma enemy. |
| [Wave routing](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140/job/139151261044) | 5 | Initial flowfield hash `629058db4926d64e`. Four wave records matched for spawned units, positions, distances, survivors and ammo. |
| [Ground Zero](https://github.com/levkeveli/Min3DStry/actions/runs/45598590140/job/139151258210) | 1 | Mined 20 copper, built a conveyor and wall, deconstructed the wall, reached wave 2, and reloaded the save with 16 copper and fingerprint `9d0956b4bcb0a6f5`. |
| **Total** | **13** | **13 of 13 matched per host C configuration across these selected checkpoints.** |

Save each execution's log separately and compare their ordered records. The
command reports missing, extra and divergent records as JSON and returns a
failure if the logs contain no selected checkpoints.

```sh
python3 tools/compare_upstream_traces.py factory-jvm.log factory-o2.log \
  --select '^UPSTREAM_(FACTORY updates|DRILL updates)'
```

## Where to extend measurement

- **Dense live gameplay.** Extend the same checkpoint format to a full unified
  ARM11 replay of a large campaign save. Compare resource totals, active
  buildings, spawn and kill ticks, and saved state against the pinned JVM.
  Relevant work includes [dense-map loading #538](https://github.com/levkeveli/Min3DStry/issues/538)
  and [Serpulo 242 save/reload #607](https://github.com/levkeveli/Min3DStry/issues/607).
- **Physical performance.** Capture sustained FPS, p95 frame time and peak
  memory on the required physical New 3DS/2DS, with workload counters and the
  exact ROM SHA. The existing harness benchmarks and Azahar runs support
  diagnosis. [High-load measurement #678](https://github.com/levkeveli/Min3DStry/issues/678)
- **Live features.** Add stock reference cases for the
  [logic editor #685](https://github.com/levkeveli/Min3DStry/issues/685),
  [LAN discovery #683](https://github.com/levkeveli/Min3DStry/issues/683),
  [title flow #650](https://github.com/levkeveli/Min3DStry/issues/650), and
  [Core Database #645](https://github.com/levkeveli/Min3DStry/issues/645).
  Compare the same inputs, selected items and saved or networked outcomes on
  each side. Use the unmodified Java v160.8 server for network and save readback.

The [mechanics ledger](../mechanics-parity.json) reports verification maturity
by feature. This comparison adds measured outcomes for selected scenarios and
leaves the ledger scores tied to their existing verification evidence.
