# Java-to-native feasibility POC

Research artifacts for [issue #1](https://github.com/levkeveli/Min3DStry/issues/1).
The analysis and results are in
[docs/research/java-to-native.md](../../docs/research/java-to-native.md).

| File | Purpose |
|---|---|
| `sim_kernel.c` | Integer-only conveyor/router/turret simulation with a deterministic trace hash. Compiles for the host and freestanding for ARMv6K. |
| `budget_model.py` | Memory and cycle budget model with explicit assumptions. |
| `run_poc.py` | Reproducible runner: host builds, determinism check, throughput, ARMv6K cross-compile and ELF verification. |

## Run

```sh
# From the repository root:
./dev.sh poc
# or
python3 research/java-to-native/run_poc.py --require-compiler
python3 research/java-to-native/budget_model.py
```

The runner prints evidence labels. Host results are `host-tested`; the ARM
object is cross-compiled only and is never executed on an emulator or console.
If no `arm-none-eabi-gcc` is available the ARM step reports `not-tested` and
does not fail. The budget model is analytical, not measured.

No proprietary Nintendo code, firmware or third-party assets are included.
