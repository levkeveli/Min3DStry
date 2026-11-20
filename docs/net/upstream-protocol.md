# Upstream v160.8 Network Framing & Handshake

**Issue:** [#31](https://github.com/levkeveli/Min3DStry/issues/31)  
**Stage:** `host-tested` (protocol codec & handshake verified live against unmodified Mindustry v8 build `160.8` `server-release.jar`; 3DS `libctru` socket adapter compiled into `min3dstry.3dsx`; on-console Wi-Fi session pending)

---

## Pinned Upstream Protocol Reference

- **Mindustry:** [`19a0f40d44514f4802a8b79770456e7e32e34da0`](https://github.com/Anuken/Mindustry/tree/19a0f40d44514f4802a8b79770456e7e32e34da0) (`v160.8`, build `160`, versionType `"official"`, GPLv3)
  - `core/src/mindustry/net/ArcNetProvider.java` (TCP length framing, `PacketSerializer` envelope + LZ4 compression flag, `FrameworkMessage` IDs)
  - `core/src/mindustry/net/Net.java` (packet ID registration order `0..6`)
  - `core/src/mindustry/net/Packets.java` (`ConnectPacket`, `StreamBegin`, `StreamChunk`)
  - `core/src/mindustry/net/NetworkIO.java` (`writeServerData` / `readServerData` UDP discovery response)
- **Arc:** [`7445105cd2683daf5b9ac7033ebb07265d3e814d`](https://github.com/Anuken/Arc/tree/7445105cd2683daf5b9ac7033ebb07265d3e814d) (`NetSerializer`, `FrameworkMessage`, `Client`/`Server` `RegisterTCP` + `RegisterUDP` handshake)

---

## Wire Format Implemented in `mindustry_net`

- **Code:** [include/net/mindustry_net.h](../../include/net/mindustry_net.h), [src/net/mindustry_net.c](../../src/net/mindustry_net.c)
- **3DS platform adapter:** [src/platform/3ds/net_3ds.h](../../src/platform/3ds/net_3ds.h), [src/platform/3ds/net_3ds.c](../../src/platform/3ds/net_3ds.c) (`libctru` `socInit` initialized on demand with a 1 MiB aligned buffer, not allocated until networking starts)

1. **TCP Outer Framing:** `uint16_be payload_len` + `payload` bytes.
2. **ArcNet `FrameworkMessage` (`0xFE` sentinel + id):**
   - `0` `Ping(i32 id, u8 isReply)`
   - `1` `DiscoverHost` (UDP ping request `[0xFE, 0x01]`)
   - `2` `KeepAlive`
   - `3` `RegisterUDP(i32 connectionID)` (client sends over UDP; server acknowledges over TCP)
   - `4` `RegisterTCP(i32 connectionID)` (note: Arc assigns signed 32-bit IDs which may be negative)
3. **UDP `DiscoverHost` Response (`NetworkIO.writeServerData`):**
   - `str8 name`, `str8 map`, `i32 players`, `i32 wave`, `i32 version`, `str8 vertype`, `u8 gamemode`, `i32 playerLimit`, `str8 description`, `str8 modeName`, `u16 port` (defaults to `6567` if `0`).
4. **PacketSerializer Envelope:**
   - `[u8 packet_id][u16_be uncompressed_len][u8 compression][payload...]`
   - Supports uncompressed transmission (`compression = 0`) and decodes both uncompressed and LZ4-compressed (`compression = 1`) server packets via a bounds-checked pure C11 LZ4 block decompressor (`m3ds_net_lz4_decompress`).
5. **`ConnectPacket` (packet ID `3`):**
   - `i32 version` (`160`), `TypeIO` strings (`versionType`, `name`, `locale`, `usid`), 8-byte raw UUID + 8-byte big-endian IEEE 802.3 CRC32 (16 bytes total), `u8 mobile`, `u32 rgba_color`, `u8 mod_count` + mod strings.

---

## Verification Evidence

### 1. Host Unit Tests (`./dev.sh test`)
- [tests/net/test_mindustry_net.c](../../tests/net/test_mindustry_net.c): **Host-tested** (TCP length framing, all `FrameworkMessage` types including negative connection IDs, `NetworkIO.writeServerData` decoding, `ConnectPacket` + CRC32 round-trip, LZ4 RLE/bounds decoding, and mock-transport full handshake with `KeepAlive` + `Ping` reply + `StreamBegin`).

### 2. Live Unmodified Java PC v160.8 Verification (`tools/verify_upstream_net.py`)
Tested against unmodified official `server-release.jar` (`sha256: faeaf247a8b622de940181a96cb21cb20692d3c3da4e8ac3f79cb4af5059aa6c`, `Mindustry 8-release official / build 160.8`) on `127.0.0.1:16567` hosting `Glacier`:

```text
PING_OK name=Server map=Glacier players=0 wave=1 version=160 vertype=official limit=30 port=16567
HANDSHAKE status=0 conn_id=-504119399 udp=1 stream_id=1 stream_total=11540 stream_type=2 first_pkt=0 payload_len=9 pings=0
HANDSHAKE status=1 conn_id=1322394541 udp=1 stream_id=-1 stream_total=0 stream_type=0 first_pkt=66 payload_len=1 pings=0
```

Server log excerpt:
```text
[I] Min3DStryOK has disconnected. [QUJDREVGR0gAAAAAaNy2HA==] (error)
[I] Kicking connection 127.0.0.1 / AAAAAAAA; Reason: clientOutdated
```

- Compatible build `160` + `versionType="official"` completed `RegisterTCP` -> `RegisterUDP` -> `ConnectPacket` and received `StreamBegin` (`stream_id=1`, `total=11540`, `type=2` `WorldStream`).
- Mismatched build `159` was cleanly rejected by the server (`clientOutdated`, packet ID `66`).

### 3. 3DS ARM11 Build (`./dev.sh build`)
- **3DS-build-tested:** `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` compile cleanly under devkitARM `arm-none-eabi-gcc` (`-std=c11 -Wall -Wextra -Werror`) into `build/min3dstry.3dsx`.
- **Not yet tested:** Physical Original/New 3DS Wi-Fi handshake via `libctru` `socInit`, full `WorldStream` ingestion into the live game state, and ongoing multiplayer gameplay packets (`#44`).

---

## License Audit

All C wire/framing code in `src/net/mindustry_net.c` and `src/platform/3ds/net_3ds.c` was written cleanly for Min3DStry from the wire specification documented in pinned upstream Mindustry (`GPLv3`) and Arc (`Apache-2.0` / `BSD-style` ArcNet heritage), with zero proprietary Nintendo code and zero undistributable third-party binaries.
