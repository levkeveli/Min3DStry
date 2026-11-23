# Stock multiplayer profiles

The ARM11 client uses the UUID created by Mindustry's stock `Platform` with
the per-server user ID created by `NetClient`. These IDs are stored in the
same SD settings file as the rest of the port's game settings. The client
sends the stock player name, locale and `color-0` settings with the original
`ConnectPacket` encoding.

Set a name for each 3DS by putting a UTF-8 line in
`/3ds/min3dstry/player-name.txt`. This overrides the saved stock name. The
current fallback for a new profile is `Min3DStry ARM11`. The original Java
server validates the names and IDs.

Use the pinned upstream runtime options with `runStockPlayerIdentityJvm`
to verify that separate installations produce different UUIDs, reopening
a profile preserves its IDs, the USID changes with the server, and the
native string data decodes with the original Java format.

The [devkitARM build](https://github.com/levkeveli/Min3DStry/actions/runs/45194412484)
passed against the unmodified strict v160.8 Java server. The ROM SHA-256 was
`2858d52757e97504956ebe28350d42282f934ac1ea970cd4f83fed25e722f226`.
Azahar 2126.2 ran Original and New 3DS in separate portable directories.
Each player had a different UUID and USID, and both received the next
authoritative wave snapshot together. After the Original profile left and
rejoined, the server observed the same UUID and USID. The local settings
payload stayed byte-identical across reconnect.

Use `tools/verify_stock_player_profiles.py` with the two portable Azahar
instances, the original v160.8 atlas, stock server JAR, and matching ROM
to reproduce the test.
