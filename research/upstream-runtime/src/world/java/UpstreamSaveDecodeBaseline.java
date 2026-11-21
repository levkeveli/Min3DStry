import arc.files.Fi;
import java.util.Locale;
import mindustry.Vars;
import mindustry.io.SaveIO;

/**
 * Decodes a real upstream .msav through the pinned v160.8 SaveIO and prints the
 * same building fields that the native Min3DStry reader hashes. The aggregate
 * hash is compared with SECTOR_PARITY on the 3DS build.
 */
public final class UpstreamSaveDecodeBaseline {
    // Matches the native reader's MSAV_HASH_SEED (the repo's established seed).
    private static final long SEED = 1469598103934665603L;
    private static final long PRIME = 0x100000001b3L;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to a .msav save");
        }
        Fi save = new Fi(args[0]);
        var app = UpstreamWorldLoadProbe.initialize(save.parent());
        SaveIO.load(save);
        app.runPosted();

        long aggregate = SEED;
        int buildings = 0;
        for (var tile : Vars.world.tiles) {
            var build = tile.build;
            if (build == null || build.tile != tile) {
                continue;
            }
            buildings++;

            long hash = SEED;
            hash = mix(hash, tile.x);
            hash = mix(hash, tile.y);
            hash = mix(hash, tile.block().id);
            hash = mix(hash, build.team.id);
            hash = mix(hash, build.rotation);
            hash = mix(hash, Float.floatToIntBits(build.health));
            hash = mix(hash, build.enabled ? 1 : 0);

            int itemCount = 0;
            int itemTotal = 0;
            long itemHash = SEED;
            int firstItem = -1;
            int firstItemAmount = 0;
            for (var item : Vars.content.items()) {
                int amount = build.items == null ? 0 : build.items.get(item);
                if (amount > 0) {
                    itemCount++;
                    itemTotal += amount;
                    itemHash = mix(itemHash, item.id);
                    itemHash = mix(itemHash, amount);
                    if (firstItem < 0) {
                        firstItem = item.id;
                        firstItemAmount = amount;
                    }
                }
            }
            hash = mix(hash, itemCount);
            hash = mix(hash, itemTotal);
            hash = mix64(hash, itemHash);

            int links = build.power == null ? 0 : build.power.links.size;
            long linkHash = SEED;
            for (int i = 0; i < links; i++) {
                linkHash = mix(linkHash, build.power.links.get(i));
            }
            hash = mix(hash, links);
            hash = mix64(hash, linkHash);
            float status = build.power == null ? 0f : build.power.status;
            hash = mix(hash, Float.floatToIntBits(status));

            int liquidCount = 0;
            long liquidHash = SEED;
            int firstLiquid = -1;
            float firstLiquidAmount = 0f;
            for (var liquid : Vars.content.liquids()) {
                float amount = build.liquids == null ? 0f : build.liquids.get(liquid);
                if (amount > 0f) {
                    liquidCount++;
                    liquidHash = mix(liquidHash, liquid.id);
                    liquidHash = mix(liquidHash, Float.floatToIntBits(amount));
                    if (firstLiquid < 0) {
                        firstLiquid = liquid.id;
                        firstLiquidAmount = amount;
                    }
                }
            }
            hash = mix(hash, liquidCount);
            hash = mix64(hash, liquidHash);

            aggregate = mix64(aggregate, hash);
            System.out.printf(Locale.ROOT,
                "UPSTREAM_SAVE_BUILDING x=%d y=%d block=%d team=%d rot=%d"
                    + " health=%.0f enabled=%d items=%d item0=%d:%d"
                    + " links=%d status=%.1f liquids=%d liquid0=%d:%.2f"
                    + " hash=%016x%n",
                tile.x, tile.y, tile.block().id, build.team.id, build.rotation,
                build.health, build.enabled ? 1 : 0, itemCount,
                firstItem, firstItemAmount, links, status, liquidCount,
                firstLiquid < 0 ? 0 : firstLiquid, firstLiquidAmount, hash);
        }
        System.out.printf(Locale.ROOT,
            "UPSTREAM_SAVE_STATE buildings=%d hash=%016x%n", buildings, aggregate);
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * PRIME;
    }

    private static long mix64(long hash, long value) {
        return (hash ^ value) * PRIME;
    }
}
