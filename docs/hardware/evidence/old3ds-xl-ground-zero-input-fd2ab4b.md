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
