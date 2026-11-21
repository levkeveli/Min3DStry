import arc.files.Fi;
import mindustry.Vars;
import mindustry.io.SaveIO;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/**
 * Streams the upstream SaveIO world into native M3dsWorldTileRow and
 * M3dsWorldBuildingRow arrays through TeaVM @Import calls. The native side
 * allocates the resident arrays with m3ds_world_rows_alloc and checks the
 * same hashes as the native reader.
 */
public final class UpstreamSaveRowsProbe {
    @Include(value = "m3ds_save_rows.h", isSystem = true)
    private static final class NativeRows {
        @Import(name = "m3ds_save_rows_begin")
        static native int begin(int width, int height, int tiles, int buildings);

        @Import(name = "m3ds_save_rows_tile")
        static native void tile(int index, int floor, int overlay, int block,
            int x, int y, int flags, int buildingIndex);

        @Import(name = "m3ds_save_rows_building")
        static native void building(int index, int tileIndex, int x, int y,
            int block, int team, int rotation, int enabled, int modules,
            int healthBits, int itemCount, int powerLinks, int powerStatusBits,
            int liquidCount, int liquidId, int liquidAmountBits, int extraSize,
            int stateLow, int stateHigh);

        @Import(name = "m3ds_save_rows_end")
        static native int end();
    }

    public static void main(String[] args) throws Exception {
        Fi save = new Fi(args.length > 0 ? args[0] : "/3ds/min3dstry/glacier-v13.msav");
        var app = UpstreamWorldLoadProbe.initialize(save.parent());
        SaveIO.load(save);
        app.runPosted();

        int width = Vars.world.width();
        int height = Vars.world.height();
        int tileCount = width * height;
        int buildingCount = 0;
        for (var tile : Vars.world.tiles) {
            if (tile == null) {
                throw new IllegalStateException("Missing tile");
            }
            if (tile.build != null && tile.build.tile == tile) {
                buildingCount++;
            }
        }
        if (NativeRows.begin(width, height, tileCount, buildingCount) != 0) {
            throw new IllegalStateException("Native row allocation failed");
        }

        int index = 0;
        int buildingIndex = 0;
        for (var tile : Vars.world.tiles) {
            boolean origin = tile.build != null && tile.build.tile == tile;
            NativeRows.tile(index++, tile.floor().id, tile.overlay().id,
                tile.block().id, tile.x, tile.y, origin ? 4 : 0,
                origin ? buildingIndex : -1);
            if (origin) {
                buildingIndex++;
            }
        }

        buildingIndex = 0;
        for (var tile : Vars.world.tiles) {
            var build = tile.build;
            if (build == null || build.tile != tile) {
                continue;
            }
            int modules = (build.items != null ? 1 : 0)
                | (build.power != null ? 2 : 0)
                | (build.liquids != null ? 4 : 0);
            long hash = buildingStateHash(tile, build);
            NativeRows.building(buildingIndex++, tile.x + tile.y * width,
                tile.x, tile.y, tile.block().id, build.team.id,
                build.rotation, build.enabled ? 1 : 0, modules,
                Float.floatToIntBits(build.health), 0, 0, 0, 0, 0, 0, 0,
                (int)(hash & 0xffffffffL), (int)(hash >>> 32));
        }

        int result = NativeRows.end();
        System.out.println("UPSTREAM_SAVE_ROWS status=" + result);
        if (result != 0) {
            throw new IllegalStateException("Native row parity differs");
        }
    }

    private static long buildingStateHash(
        mindustry.world.Tile tile, mindustry.gen.Building build) {
        final long seed = 1469598103934665603L;
        long hash = seed;
        hash = mix(hash, tile.x);
        hash = mix(hash, tile.y);
        hash = mix(hash, tile.block().id);
        hash = mix(hash, build.team.id);
        hash = mix(hash, build.rotation);
        hash = mix(hash, Float.floatToIntBits(build.health));
        hash = mix(hash, build.enabled ? 1 : 0);

        int itemCount = 0;
        int itemTotal = 0;
        long itemHash = seed;
        for (var item : Vars.content.items()) {
            int amount = build.items == null ? 0 : build.items.get(item);
            if (amount > 0) {
                itemCount++;
                itemTotal += amount;
                itemHash = mix(itemHash, item.id);
                itemHash = mix(itemHash, amount);
            }
        }
        hash = mix(hash, itemCount);
        hash = mix(hash, itemTotal);
        hash = mix64(hash, itemHash);

        int links = build.power == null ? 0 : build.power.links.size;
        long linkHash = seed;
        for (int i = 0; i < links; i++) {
            linkHash = mix(linkHash, build.power.links.get(i));
        }
        hash = mix(hash, links);
        hash = mix64(hash, linkHash);
        hash = mix(hash, Float.floatToIntBits(
            build.power == null ? 0f : build.power.status));

        int liquidCount = 0;
        long liquidHash = seed;
        for (var liquid : Vars.content.liquids()) {
            float amount = build.liquids == null ? 0f : build.liquids.get(liquid);
            if (amount > 0f) {
                liquidCount++;
                liquidHash = mix(liquidHash, liquid.id);
                liquidHash = mix(liquidHash, Float.floatToIntBits(amount));
            }
        }
        hash = mix(hash, liquidCount);
        return mix64(hash, liquidHash);
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    private static long mix64(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
