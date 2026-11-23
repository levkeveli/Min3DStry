# Original 3DS XL Ground Zero input diagnostics

On 2026-11-22 an Original 3DS XL received standalone Mindustry v160.8
diagnostic build `fd2ab4b` over NetLoader. The ROM SHA-256 was
`48506d87cf0267434598230eaaf21c2bd0e7b9962ffc5177a4e387ede73b2733`.
The host received logs while the game resumed an existing Ground Zero save.

```text
UPSTREAM_MEMORY appHeap=45588480 linearHeap=4194304 stack=262144
UPSTREAM_SP_LOADED source=save map=Ground_Zero size=256x256 wave=8 copper=0
UPSTREAM_BUILD_TOUCH x=264 y=154 mine=false viewport=123,48
UPSTREAM_TOUCH_PLAN block=conveyor x=137 y=53 rotation=0
UPSTREAM_BUILD_TOUCH x=314 y=96 mine=false viewport=123,48
UPSTREAM_MINE_MODE active=true
UPSTREAM_BUILD_TOUCH x=270 y=150 mine=true viewport=123,48
UPSTREAM_MINE_TARGET x=137 y=53 item=copper
UPSTREAM_BUILD_TOUCH x=270 y=146 mine=true viewport=123,48
UPSTREAM_MINE_CANCEL x=137 y=53
UPSTREAM_BUILD_TOUCH x=268 y=158 mine=true viewport=123,48
UPSTREAM_MINE_TARGET x=137 y=53 item=copper
```

The taps reached the game and selected copper. The second tap in the same
grid cell canceled that target. Earlier taps in build mode queued conveyor
plans. Upstream mining pauses while the unit is actively building; the logs
do not establish whether active plans delayed mining in this session.

## Pause Building follow-up

On the same Original 3DS XL, build `ead6427` resumed the Ground Zero save.
Its ROM SHA-256 was
`7ebd62042050c7c8971367038e04991c99569ddf3d389471bd8599a9a3b15932`.

```text
UPSTREAM_SP_BUILDING paused=true queued=5
UPSTREAM_MINE_TARGET x=137 y=53 item=copper
UPSTREAM_SP_BUILDING paused=false queued=5
UPSTREAM_SP_BUILDING paused=true queued=5
UPSTREAM_MINE_REJECT x=137 y=51 reason=inventory
UPSTREAM_MINE_REJECT x=137 y=53 reason=inventory
```

The operator saw a mining animation. Pause and Resume both responded and
retained five plans. The trace recorded no increase in core copper. Later
taps reached the ore check but failed the unit inventory check. This build
did not report whether the unit held copper or another item.

## Mining inventory follow-up

Build `76d1e2b` (ROM SHA-256
`64a6a748abac4bd5bae286e640c0010cb9696890c1637e5091fe78538cffa063`)
resumed the wave 8 save. With construction active, the mining timer stayed
at zero across hundreds of simulation ticks. The Alpha's stack was empty
and its core was initially in transfer range. The team core later became
unavailable during the active wave.

```text
UPSTREAM_SP_MINING tile=133,46 tick=784 progress=0.0 building=true player=true
UPSTREAM_SP_MINING_INVENTORY held=copper:0 core=present inRange=true copper=0
UPSTREAM_SP_MINING tile=133,46 tick=838 progress=0.0 building=true player=true
UPSTREAM_SP_MINING_INVENTORY held=copper:0 core=none inRange=false copper=0
```

Later in the same session, with construction paused, the Alpha successfully
mined and carried five copper. The core was still unavailable, so copper
could not reach the core HUD counter.

```text
UPSTREAM_MINE_TARGET x=137 y=53 item=copper
UPSTREAM_SP_MINING tile=137,53 tick=55974 progress=39.0 building=false player=true
UPSTREAM_SP_MINING_INVENTORY held=copper:5 core=none inRange=false copper=0
```
