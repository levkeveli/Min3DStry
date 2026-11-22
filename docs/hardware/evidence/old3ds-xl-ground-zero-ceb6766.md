# Original 3DS XL Ground Zero play session

- Date: 2026-11-22
- Runtime: standalone upstream Mindustry v160.8 ARM11 single-player build
- Artifact commit: `ceb67666f97e393e183153024bb6cff475d757f0`
- ROM SHA-256: `81bab5d4576f4189a3d897a79919dd2aa1669da65cbdfa851fef3644751dc775`
- Build source: [Custom Game verification artifact](https://github.com/levkeveli/Min3DStry/actions/runs/45032641678)

NetLoader transferred the standalone build over Wi-Fi. The operator confirmed
that it reached gameplay and that the Circle Pad moved the player.

Touch mining did not work during the session. The operator retried the Mine
and ore-selection sequence without success. The A button on this console
does not work. The mining path accepts two touch presses independently of A.
This build emits gameplay diagnostics to the debugger, so the NetLoader
session could not establish whether the taps reached the game or ore
selection was rejected.

Building, wave play, save and reload, audio, memory use and frame rate
remain unverified for this standalone build on this console.
