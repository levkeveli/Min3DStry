# Upstream v160.8 Network Framing & Handshake

**Issue:** [#31](https://github.com/levkeveli/Min3DStry/issues/31) / [#44](https://github.com/levkeveli/Min3DStry/issues/44)

**Stage:** `host-tested` (wire-exact framing & host session baseline verified live against unmodified Mindustry v8 build `160.8` `server-release.jar` via `tools/verify_upstream_net.py`; `net_3ds.c` compiled into `min3dstry.3dsx`; separate TeaVM ARM11 Azahar sessions are tracked in [`research/upstream-runtime/`](../../research/upstream-runtime/README.md) and [`port-capabilities.json`](../port-capabilities.json); physical New Nintendo 3DS/2DS Wi-Fi sessions remain pending under `#44` / `#43`)

---

## Architectural Role & 1:1 Port Boundary

- **Reusable 3DS Socket Transport Adapter ([`src/platform/3ds/net_3ds.c`](../../src/platform/3ds/net_3ds.c)):** Provides on-demand `libctru` `socInit` initialization (1 MiB `memalign(0x1000, 0x100000)` buffer) and non-blocking BSD TCP/UDP sockets behind `M3dsNetTransport`, replacing desktop Java NIO `Selector` / `SocketChannel` / `DatagramChannel` on ARM11.
- **Issue #31 Wire-Exact Framing & Handshake Oracle ([`include/net/mindustry_net.h`](../../include/net/mindustry_net.h), [`src/net/mindustry_net.c`](../../src/net/mindustry_net.c)):** Fulfils Issue #31 (`include/net/` framing + connect/disconnect handshake verified against unmodified Java PC v160.8) using byte-exact codecs matching pinned `ArcNetProvider.java:33,479-605`, `NetworkIO.java:188-231`, `Packets.java`, `Net.java`, and Arc `7445105cd2`.
- **Not a Parallel Gameplay Net Stack:** Full multiplayer gameplay sessions ([#44](https://github.com/levkeveli/Min3DStry/issues/44)) will adapt TeaVM-compiled upstream `NetClient`/`NetServer` and packet handlers over the `net_3ds.c` `libctru` socket adapter rather than expanding `mindustry_net.c` into a bespoke C gameplay client.

---

## Pinned Upstream Protocol Reference

- **Mindustry:** [`19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0) (`v160.8`, build `160`, versionType `"official"`, GPLv3)
  - `core/src/mindustry/net/ArcNetProvider.java` (lines `33`, `479-605`: TCP length framing, `PacketSerializer` envelope + `0`/`1` raw/LZ4 compression byte, `FrameworkMessage` IDs, 512-byte discovery buffer)
  - `core/src/mindustry/net/Net.java` (packet ID registration order `0..6`)
  - `core/src/mindustry/net/Packets.java` (`ConnectPacket`, `StreamBegin`, `StreamChunk`)
  - `core/src/mindustry/net/NetworkIO.java` (lines `188-231`: `writeServerData` / `readServerData` UDP discovery payload written into 500 bytes)
- **Arc:** [`7445105cd2683daf5b9ac7033ebb07265d3e814d`](https://github.com/Anuken/Arc/tree/7445105cd2683daf5b9ac7033ebb07265d3e814d) (`NetSerializer`, `FrameworkMessage`, `Client`/`Server` `RegisterTCP` + `RegisterUDP` handshake)

---

## Wire Format Implemented in `mindustry_net`

- **Code:** [include/net/mindustry_net.h](../../include/net/mindustry_net.h), [src/net/mindustry_net.c](../../src/net/mindustry_net.c)
- **3DS platform adapter:** [src/platform/3ds/net_3ds.h](../../src/platform/3ds/net_3ds.h), [src/platform/3ds/net_3ds.c](../../src/platform/3ds/net_3ds.c) (`libctru` `socInit` initialized on demand with a 1 MiB aligned buffer, not allocated until networking starts; descriptor cleanup on reconnect)

### Host transport

The host registration and frame reader use pinned ArcNet's server order.
The TCP listener's connection ID is sent in `RegisterTCP`. After the peer's
UDP socket returns that ID in `RegisterUDP`, the host sends the stock
acknowledgement over TCP. The TCP reader returns only complete incoming
frames and leaves incomplete bytes with the caller. It applies the stock
provider's 16 KiB incoming object-buffer bound.

The host C tests cover split and coalesced frames, rejected registrations,
and the stock acknowledgement. The nonblocking socket adapter queues TCP
frames, receives UDP by registered endpoint, and exposes connection,
disconnect and discovery events to the ARM11 bridge. The host listens for
the stock direct broadcast and joins the stock multicast discovery group
when the socket service supports it. A local loopback check
used the unmodified v160.8 Arc client and packet serializer to complete
registration, deliver the original `ConnectPacket` and a UDP ping, and
disconnect. The stock world stream and gameplay dispatch through `NetServer`
are tracked in [#713](https://github.com/levkeveli/Min3DStry/issues/713).

The host packet adapter uses the original v160.8 generated packet types and
their stock read/write methods. It selects the bundled pure-Java LZ4
implementation directly for TeaVM. The JVM task `runStockHostPacketCodecJvm`
exchanges compressed `ConnectPacket` data with the stock desktop serializer
in both directions and checks the same raw framing for `StreamChunk`.
The hosted provider hands original packets to `Net.handleServerReceived`
and queues world streams as stock `StreamBegin` and `StreamChunk` packets.
It uses `NetworkIO.writeServerData` for LAN advertisements. The unified
app starts the stock `NetServer` after world load and advances it each
frame. In a loaded world, open the pause menu and press R to host or close
the server. The game keeps simulating while guests are connected and the
menu is open. Hold X and Y at startup or pass `--host` to host on launch.
The build adapts the stock debug message for stream sizes to use bytes
because TeaVM lacks the Java formatter.

The hosted-world Azahar replay uses the unmodified v160.8 Java transport
to receive a world stream. Stock `NetworkIO.loadWorld` opens the map and
checks the player's core before the client confirms the join, waits for the
original server's first state snapshot, and sends a chat message through the
stock handler. The same client check loaded Glacier and received its chat
echo from the stock Java server. The native replay and desktop gameplay
checks are pending.

```sh
python3 tools/verify_upstream_hosted_world.py --rom upstream-probe.3dsx \
  --stock-jar server-release.jar --output build/hosted-world-smoke
```

1. **TCP Outer Framing:** `uint16_be payload_len` + `payload` bytes, read via stateful `TcpFrameReader` so partial headers/bodies across TCP segments and short UDP poll windows resume cleanly without dropping bytes.
2. **ArcNet `FrameworkMessage` (`0xFE` sentinel + id):**
   - `0` `Ping(i32 id, u8 isReply)` (answered automatically across all handshake receive loops)
   - `1` `DiscoverHost` (UDP ping request `[0xFE, 0x01]`)
   - `2` `KeepAlive`
   - `3` `RegisterUDP(i32 connectionID)` (client sends over UDP; server acknowledges over TCP)
   - `4` `RegisterTCP(i32 connectionID)` (note: Arc assigns signed 32-bit IDs which may be negative)
3. **UDP `DiscoverHost` Response (`NetworkIO.writeServerData`):**
   - `str8 name`, `str8 map`, `i32 players`, `i32 wave`, `i32 version`, `str8 vertype`, `u8 gamemode`, `i32 playerLimit`, `str8 description`, `str8 modeName`, `u16 port` (defaults to `6567` if `0`).
   - Upstream writes into a 500-byte `ByteBuffer` and reads into 512 bytes (`NetworkIO.java:193`, `ArcNetProvider.java:33`); `m3ds_net_ping_host()` uses a 1024-byte receive buffer for headroom against oversized responses.
4. **PacketSerializer Envelope (`ArcNetProvider.java:516-580`):**
   - `[u8 packet_id][u16_be uncompressed_len][u8 compression][payload...]`
   - Upstream `PacketSerializer` uses only `0` (raw uncompressed, always used when uncompressed length `< 36` or `StreamChunk`) and `1` (raw LZ4 block via `net.jpountz.lz4`; deflate is never used). `m3ds_net_decode_packet()` rejects any other compression byte.
   - `compression == 1` (`net.jpountz.lz4`) decompression is verified both by a deterministic vector emitted by pinned `ArcNetProvider.PacketSerializer` in [tests/net/test_mindustry_net.c](../../tests/net/test_mindustry_net.c) and live during `tools/verify_upstream_net.py` by decoding compressed server snapshot frames (`compression=1`) captured from unmodified `server-release.jar`.
5. **`ConnectPacket` (packet ID `3`):**
   - `i32 version` (`160`), `TypeIO` strings (`versionType`, `name`, `locale`, `usid`), 8-byte raw UUID + 8-byte big-endian IEEE 802.3 CRC32 (16 bytes total), `u8 mobile`, `u32 rgba_color`, `u8 mod_count` + mod strings.

---

## Verification Evidence

### 1. Host Unit Tests (`./dev.sh test`)
- [tests/net/test_mindustry_net.c](../../tests/net/test_mindustry_net.c): **Host-tested** (TCP length framing, all `FrameworkMessage` types including negative connection IDs, `NetworkIO.writeServerData` decoding including >512-byte datagrams, `ConnectPacket` + CRC32 round-trip, LZ4 RLE/bounds decoding + non-0/1 compression byte rejection, mock-transport full handshake with `KeepAlive` + `Ping` reply across steps + `StreamBegin`, 1-byte TCP segmentation with mid-frame timeout recovery, and early non-framework kick reporting).

### 2. Live Unmodified Java PC v160.8 Verification (`tools/verify_upstream_net.py`)
Tested against unmodified official `server-release.jar` (`sha256: faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`, `Mindustry 8-release official / build 160.8`) on `127.0.0.1:16567` hosting `Glacier`:

```text
PING_OK name=Server map=Glacier players=0 wave=1 version=160 vertype=official limit=30 port=16567
HANDSHAKE status=0 conn_id=1293980436 udp=1 stream_id=1 stream_total=11492 stream_type=2 first_pkt=0 payload_len=9 pings=0
HANDSHAKE status=1 conn_id=-559963639 udp=1 stream_id=-1 stream_total=0 stream_type=0 first_pkt=66 payload_len=1 pings=0
```

Server log excerpt:
```text
[I] Min3DStryOK has disconnected. [QUJDREVGR0gAAAAAaNy2HA==] (error)
[I] Kicking connection 127.0.0.1 / AAAAAAAA; Reason: clientOutdated
```

- Compatible build `160` + `versionType="official"` completed `RegisterTCP` -> `RegisterUDP` -> `ConnectPacket` and received `StreamBegin` (`stream_id=1`, `total=11492`, `type=2` `WorldStream`). Arc assigns a fresh signed 32-bit `conn_id` per TCP connection (and serialized world stream byte counts vary slightly across freshly started `Glacier` server instances).
- Mismatched build `159` was cleanly rejected by the server (`clientOutdated`, packet ID `66`).

### 3. End-to-End Live Session Baseline (`#44` — `UpstreamNetSessionBaseline.java` + LZ4 Cross-Check)
`tools/verify_upstream_net.py` also runs [UpstreamNetSessionBaseline.java](../../research/upstream-runtime/host/UpstreamNetSessionBaseline.java) against the unmodified v160.8 `server-release.jar` and cross-verifies a captured live `compression == 1` frame through C11 `m3ds_net_decode_packet`:

```text
UPSTREAM_NET_DISCOVERY map=Glacier version=160 vertype=official players=0 wave=1
UPSTREAM_NET_MISMATCH build=159 kick=clientOutdated
UPSTREAM_NET_ACTION run=1 op=build conveyor=85,26@rot1 wall=86,26 copper=200->193 buildings=2->4
UPSTREAM_LZ4_PACKET id=141 compressed_bytes=140 payload_len=182 sha256=5114dba0d37c186c661de1ddd898ccc8f0c1facf799937c05e02e19937fded38
UPSTREAM_NET_SESSION run=1 map=Glacier width=150 height=250 buildings=4 wave=1 playerId=213 state_snapshots=4 entity_snapshots=3 lz4_packets=8
UPSTREAM_NET_ACTION run=2 op=rejoin_deconstruct persisted=4 wall_removed=86,26 copper=193->196 buildings=4->3
UPSTREAM_NET_SESSION run=2 map=Glacier width=150 height=250 buildings=3 wave=1 playerId=219 state_snapshots=4 entity_snapshots=2 lz4_packets=7
UPSTREAM_NET_PASS sessions=2 mismatch=clientOutdated lz4_packets=15
DECODE_PKT_OK id=141 compression=1 wire_len=140 payload_len=182
```

- **Version mismatch:** Build `159` received `KickCallPacket2` with `KickReason.clientOutdated`.

- **Two bidirectional gameplay sessions (build, disconnect/rejoin persistence, and deconstruct):**
  - **Session 1 (`run=1`):** Completed `ConnectPacket` -> `WorldStream` (`150x250` `Glacier`, `2` core origins, `copper=200`) -> `ConnectConfirmCallPacket`, applied incoming `StateSnapshotCallPacket` + `EntitySnapshotCallPacket` on the main thread until the server-spawned player unit was synced, transmitted outbound UDP `ClientSnapshotCallPacket` build plans for a `Conveyor` at `(85, 26)` rotation `1` and a `Copper Wall` at `(86, 26)`, applied server `ConstructFinishCallPacket` confirmations, and verified authoritative core copper sync (`200 -> 193`, `buildings=2->4`).
    The starting Glacier tiles may be air or replaceable stock decoration, such as snow boulders. Both targets must be unoccupied by buildings.
  - **Session 2 (`run=2`):** Reset client state (`Vars.logic.reset()`), reconnected with the same UUID, confirmed the server's fresh rejoin `WorldStream` persisted all `4` buildings and `copper=193`, sent an outbound `ClientSnapshotCallPacket` deconstruction `BuildPlan(86, 26)`, applied `DeconstructFinishCallPacket`, and verified authoritative core copper refund sync (`193 -> 196`, `buildings=4->3`).
- **Live LZ4 parity:** The raw `net.jpountz.lz4`-compressed server packet (`id=141`, `compressed_bytes=140`, `payload_len=182`, `compression=1`) decoded byte-for-byte identically via C11 `m3ds_net_decode_packet` (`DECODE_PKT_OK`).
- **Run-to-run variability:** Because this harness connects to a freshly started live `server-release.jar`, server-assigned `playerId`s, `state_snapshots` / `entity_snapshots` / `lz4_packets` counts, and the captured `UPSTREAM_LZ4_PACKET` SHA-256 vary between runs (for example, `lz4_packets=15` and `16` across consecutive runs); `tools/verify_upstream_net.py` asserts the deterministic `UPSTREAM_NET_DISCOVERY`, `UPSTREAM_NET_MISMATCH`, both `UPSTREAM_NET_ACTION` build/rejoin/deconstruct lines, non-zero `entity_snapshots`, and C11 `DECODE_PKT_OK` invariants rather than fixed snapshot counts or hashes.

### 4. 3DS ARM11 Build (`./dev.sh build`)
- **3DS-build-tested:** `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` compile cleanly under devkitARM `arm-none-eabi-gcc` (`-std=c11 -Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Not yet tested:** Physical New Nintendo 3DS/2DS Wi-Fi handshake and play sessions (`#31`, `#44`, `#43`); this host/wire document stays at `host-tested` while separate TeaVM ARM11 emulator runs are recorded under [`research/upstream-runtime/`](../../research/upstream-runtime/README.md).

---

## Stock multiplayer chat

Press **Y** to open the 3DS touch keyboard during a live game. Use **A** to
send or **B** to cancel. The first keyboard layout covers Latin letters,
digits, punctuation, and stock server commands such as `/help`. The client
sends the original reliable `SendChatMessageCallPacket`, so the unmodified
server handles player formatting and commands. Player and server messages
use the original receive packets and stock text renderer on the top screen.
World snapshots and the TCP heartbeat continue while the keyboard is open.
Additional text entry languages remain open work.

Use `tools/verify_upstream_live_net.py --controls --verify-chat --terrain-atlas
/path/to/sprites.aatls` with the matching ROM and stock v160.8 server. The
replay sends both stock receive packet variants, types a reply on the touch
keyboard, and waits for authoritative world snapshots during and after entry.

The [devkitARM build](https://github.com/levkeveli/Min3DStry/actions/runs/45197857282)
passed the chat round trip on Original and New 3DS in Azahar 2126.2. The
ROM SHA-256 was `0e016fb9726e62703f8690fa2cdc1fdfd49dac3f827166dbd2d2d9e66586cc93`.
The stock Java server sent a notification and a formatted player line. The
client drew the newest line, accepted a touch-entered reply, and drew the
server's formatted echo. The following wave snapshot reached the client
with the composer open and another arrived after submission. A stock PC
client displayed the ARM11-origin text in the shared local session.

## Stock server kicks and restart

The 3DS client displays the stock kick reason or the message supplied by the
server. A stock `serverRestarting` reason opens the reconnect screen and
triggers discovery at the same address. Press **B** on that screen to cancel.

Run `tools/verify_upstream_live_net.py` with the matching ARM11 ROM and the
stock v160.8 server. Use `--server-kick restart` to stop and reopen the server
and require a second join. Use `--server-kick restart-cancel` to keep the server
offline, press **B**, and require cancellation without a second join.
Use `--verify-kick-notice` with `--server-kick text` or `--server-kick enum`
to check the rendered reason for a regular disconnect.

The restart replay passed on both Azahar console profiles. The cancellation
replay passed on the New 3DS profile.
[New 3DS restart](https://github.com/levkeveli/Min3DStry/actions/runs/45290486799) ·
[Original 3DS restart](https://github.com/levkeveli/Min3DStry/actions/runs/45293324404) ·
[New 3DS cancellation](https://github.com/levkeveli/Min3DStry/actions/runs/45296040600)

## License Audit

All C wire/framing code in `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` was written cleanly for Min3DStry from the wire specification documented in pinned upstream Mindustry (`GPLv3`) and Arc (`Apache-2.0` / `BSD-style` ArcNet heritage), with zero proprietary Nintendo code and zero undistributable third-party binaries.
