package port.render;

import arc.files.Fi;

/** Samples the same uncompressed RGBA8 atlas tiles used by the native renderer. */
public final class UpstreamLogicAtlas {
    private static final int tileSize = 128;
    private static final int headerBytes = 21;
    private final Fi directory;
    private final int[] pages = {-1, -1, -1, -1};
    private final int[] tileXs = new int[pages.length], tileYs = new int[pages.length];
    private final byte[][] tiles = new byte[pages.length][];
    private int nextSlot, recent;

    public UpstreamLogicAtlas(Fi directory) {
        this.directory = directory;
    }

    public int sample(int page, int x, int y) {
        int tileX = x / tileSize, tileY = y / tileSize;
        if (!matches(recent, page, tileX, tileY)) recent = find(page, tileX, tileY);
        return texel(tiles[recent], x % tileSize, y % tileSize);
    }

    private boolean matches(int slot, int page, int x, int y) {
        return pages[slot] == page && tileXs[slot] == x && tileYs[slot] == y;
    }

    private int find(int page, int x, int y) {
        for (int slot = 0; slot < tiles.length; slot++) {
            if (matches(slot, page, x, y)) return slot;
        }
        Fi file = directory.child(page + "-" + x + "-" + y + ".t3x");
        byte[] data = file.readBytes();
        if (data.length != headerBytes + tileSize * tileSize * 4) {
            throw new IllegalArgumentException("Invalid world atlas tile " + file);
        }
        int slot = nextSlot;
        nextSlot = (nextSlot + 1) % tiles.length;
        pages[slot] = page;
        tileXs[slot] = x;
        tileYs[slot] = y;
        tiles[slot] = data;
        return slot;
    }

    private static int texel(byte[] data, int x, int y) {
        int block = (y / 8) * (tileSize / 8) + x / 8;
        int morton = 0;
        for (int bit = 0; bit < 3; bit++) {
            morton |= ((x >> bit) & 1) << (2 * bit);
            morton |= ((y >> bit) & 1) << (2 * bit + 1);
        }
        int offset = headerBytes + (block * 64 + morton) * 4;
        return (data[offset + 3] & 255) << 24 | (data[offset + 2] & 255) << 16
            | (data[offset + 1] & 255) << 8 | (data[offset] & 255);
    }
}
