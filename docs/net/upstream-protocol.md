# Upstream v160.8 Network Framing & Handshake

**Issue:** [#31](https://github.com/levkeveli/Min3DStry/issues/31)  
**Stage:** `host-tested` (wire-exact Issue #31 handshake & framing oracle verified live against unmodified Mindustry v8 build `160.8` `server-release.jar`; 3DS `libctru` socket adapter compiled into `min3dstry.3dsx`; full TeaVM `NetClient` wiring and on-console Wi-Fi session pending in `#44`)

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
UPSTREAM_LZ4_PACKET id=141 compressed_bytes=140 payload_len=182 sha256=6ef5e3faea89648f3b9b627b1d9dbc837bde99a9ac1063a93734fba6082e1a78
UPSTREAM_NET_SESSION run=1 map=Glacier width=150 height=250 buildings=2 wave=1 playerId=213 state_snapshots=1 entity_snapshots=0 lz4_packets=1
UPSTREAM_NET_SESSION run=2 map=Glacier width=150 height=250 buildings=2 wave=1 playerId=215 state_snapshots=1 entity_snapshots=0 lz4_packets=1
UPSTREAM_NET_PASS sessions=2 mismatch=clientOutdated lz4_packets=2
DECODE_PKT_OK id=141 compression=1 wire_len=140 payload_len=182
```

- **Version mismatch:** Build `159` received `KickCallPacket2` with `KickReason.clientOutdated`.
- **Two full sessions (disconnect + rejoin with same UUID):** Each run completed `ConnectPacket` -> `StreamBegin`/`StreamChunk` assembly -> upstream `NetworkIO.loadWorld` (`150x250` `Glacier`, `2` core origins, valid `playerId`) -> `ConnectConfirmCallPacket` -> live `StateSnapshotCallPacket` ingestion.
- **Live LZ4 parity:** The raw `net.jpountz.lz4`-compressed server packet (`id=141`, `compressed_bytes=140`, `payload_len=182`, `compression=1`) decoded byte-for-byte identically via C11 `m3ds_net_decode_packet` (`DECODE_PKT_OK`).

### 4. 3DS ARM11 Build (`./dev.sh build`)
- **3DS-build-tested:** `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` compile cleanly under devkitARM `arm-none-eabi-gcc` (`-std=c11 -Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Not yet tested:** Physical Original/New 3DS Wi-Fi handshake and play session over TeaVM ARM11 live sockets (`#31`, `#44`); capability status remains `host-tested`.

---

## License Audit

All C wire/framing code in `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` was written cleanly for Min3DStry from the wire specification documented in pinned upstream Mindustry (`GPLv3`) and Arc (`Apache-2.0` / `BSD-style` ArcNet heritage), with zero proprietary Nintendo code and zero undistributable third-party binaries.
