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
