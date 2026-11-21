import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.AtlasPage;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.Region;
import arc.mock.MockApplication;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.ctype.UnlockableContent;
import mindustry.world.meta.BuildVisibility;

// Host-side tooling. Arc reads the original atlas metadata without creating GL textures.
class UpstreamSpriteAtlas {
    private static final String ATLAS_DIR = "assets/sprites/";

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[1]);
        try (var archive = new ZipFile(args[0])) {
            var metadata = new Fi(ATLAS_DIR + "sprites.aatls") {
                @Override
                public InputStream read() {
                    try {
                        return open(archive, ATLAS_DIR + "sprites.aatls");
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }
            };
            var atlas = new TextureAtlasData(metadata, metadata.parent(), false);
            Map<String, Region> regions = writeInventory(atlas, output);
            if (args.length == 3 && args[2].equals("--world-pages")) {
                exportWorldPages(archive, atlas, output);
                return;
            }
            // Keep only one decoded upstream page in memory at a time.
            Path sprites = Files.createDirectory(output.resolve("png"));
            if (args.length == 3 && args[2].equals("--menu-icons")) {
                exportMenuIcons(archive, atlas, regions, output, sprites);
                return;
            }
            int exported = 0;
            for (AtlasPage page : atlas.getPages()) {
                BufferedImage source = null;
                for (int index = 2; index < args.length; index++) {
                    Region region = regions.get(args[index]);
                    if (region == null) {
                        throw new IOException("No upstream sprite named " + args[index]);
                    }
                    if (region.page != page) continue;
                    if (source == null) source = readPage(archive, page);
                    exportRegion(source, region, sprites.resolve(region.name + ".png"));
                    exported++;
                }
            }
            System.out.printf("Upstream atlas: %d pages, %d regions, %d exported%n",
                atlas.getPages().size, atlas.getRegions().size, exported);
        }
    }

    private static void exportWorldPages(ZipFile archive, TextureAtlasData atlas, Path output) throws IOException {
        final int tile = 128;
        Path textures = Files.createDirectory(output.resolve("world-atlas"));
        var header = new StringBuilder("#define WORLD_ATLAS_TILE 128\n")
            .append("static const int world_atlas_sizes[][2] = {\n");
        try (var manifest = Files.newBufferedWriter(output.resolve("world-atlas.tsv"))) {
            manifest.write("page\timage\twidth\theight\n");
            int index = 0;
            for (AtlasPage page : atlas.getPages()) {
                BufferedImage pixels = readPage(archive, page);
                if (page.width % tile != 0 || page.height % tile != 0) {
                    throw new IOException("Upstream atlas page is not tile aligned: " + page.textureFile.name());
                }
                manifest.write(index + "\t" + page.textureFile.name() + "\t" + page.width + "\t" + page.height + "\n");
                header.append("    { ").append(page.width).append(", ").append(page.height).append(" },\n");
                for (int y = 0; y < page.height; y += tile) {
                    for (int x = 0; x < page.width; x += tile) {
                        String name = index + "-" + (x / tile) + "-" + (y / tile) + ".t3x";
                        writeTexture(pixels.getSubimage(x, y, tile, tile), textures.resolve(name));
                    }
                }
                index++;
            }
        }
        Files.writeString(output.resolve("world_atlas_layout.h"), header.append("};\n").toString());
    }

    private static void exportMenuIcons(ZipFile archive, TextureAtlasData atlas, Map<String, Region> regions,
            Path output, Path sprites) throws IOException {
        var lookup = new TextureAtlas();
        for (AtlasPage page : atlas.getPages()) {
            var texture = new InventoryTexture(page.width, page.height);
            for (Region region : atlas.getRegions()) {
                if (region.page == page) {
                    lookup.addRegion(region.name, texture, region.left, region.top, region.width, region.height);
                }
            }
        }
        if (!lookup.setErrorRegion("error")) throw new IOException("Missing original error region");
        Core.atlas = lookup;
        Core.app = new MockApplication();
        Core.settings = new Settings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();

        Map<String, Region> icons = new LinkedHashMap<>();
        Path catalog = Files.createDirectory(output.resolve("catalog"));
        try (var manifest = Files.newBufferedWriter(output.resolve("menu-icons.tsv"))) {
            manifest.write("file\tcontent\tregion\twidth\theight\n");
            for (var block : Vars.content.blocks()) {
                addMenuIcon(block, "block", block.placeablePlayer && block.buildVisibility != BuildVisibility.hidden,
                    regions, icons, manifest);
            }
            for (var item : Vars.content.items()) {
                addMenuIcon(item, "item", true, regions, icons, manifest);
            }
        }
        for (AtlasPage page : atlas.getPages()) {
            BufferedImage source = null;
            for (var entry : icons.entrySet()) {
                var region = entry.getValue();
                if (region.page != page) continue;
                if (source == null) source = readPage(archive, page);
                var pixels = exportRegion(source, region, sprites.resolve(entry.getKey() + ".png"));
                writeTexture(pixels, catalog.resolve(entry.getKey() + ".t3x"));
            }
        }
        System.out.printf("Original menu icons: %d of %d blocks, %d items%n",
            icons.size() - Vars.content.items().size, Vars.content.blocks().size, Vars.content.items().size);
    }

    /** Tex3DS single-region, uncompressed RGBA8 sheet. Pixels are tiled in Morton order. */
    private static void writeTexture(BufferedImage image, Path file) throws IOException {
        int sw = image.getWidth(), sh = image.getHeight();
        if (sw > 128 || sh > 128) throw new IOException("Texture exceeds 128 pixels: " + file);
        int width = Math.max(8, Integer.highestOneBit(sw - 1) << 1);
        int height = Math.max(8, Integer.highestOneBit(sh - 1) << 1);
        int dimensions = (Integer.numberOfTrailingZeros(width) - 3)
            | ((Integer.numberOfTrailingZeros(height) - 3) << 3);
        int size = width * height * 4;
        var data = ByteBuffer.allocate(21 + size).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 1).put((byte) dimensions).put((byte) 0).put((byte) 0);
        data.putShort((short) sw).putShort((short) sh).putShort((short) 0).putShort((short) 1024)
            .putShort((short) (1024 * sw / width)).putShort((short) (1024 - 1024 * sh / height));
        data.putInt(size << 8);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int tile = (y / 8) * (width / 8) + x / 8;
                int offset = 0;
                for (int bit = 0; bit < 3; bit++) {
                    offset |= ((x >> bit) & 1) << (2 * bit);
                    offset |= ((y >> bit) & 1) << (2 * bit + 1);
                }
                int argb = image.getRGB(x, y);
                data.putInt(21 + (tile * 64 + offset) * 4, (argb << 8) | (argb >>> 24));
            }
        }
        Files.write(file, data.array());
    }

    private static void addMenuIcon(UnlockableContent content, String kind, boolean required,
            Map<String, Region> regions, Map<String, Region> icons, java.io.Writer manifest) throws IOException {
        content.loadIcon();
        var icon = content.uiIcon;
        if (!(icon instanceof TextureAtlas.AtlasRegion named) || !icon.found()) {
            if (required) throw new IOException("No original menu icon for " + kind + " " + content.name);
            return;
        }
        var region = regions.get(named.name);
        String file = kind + "-" + content.id;
        icons.put(file, region);
        manifest.write(String.join("\t", file, content.name, region.name, Integer.toString(region.width),
            Integer.toString(region.height)) + "\n");
    }

    private static final class InventoryTexture extends Texture {
        InventoryTexture(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static InputStream open(ZipFile archive, String name) throws IOException {
        var entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Missing " + name + " in " + archive.getName());
        }
        return archive.getInputStream(entry);
    }

    private static Map<String, Region> writeInventory(TextureAtlasData atlas, Path output)
            throws IOException {
        try (var inventory = Files.newBufferedWriter(output.resolve("regions.tsv"))) {
            inventory.write("name\tpage\tleft\ttop\twidth\theight\toffsetX\toffsetY\toriginalWidth\toriginalHeight\trotate\tsplits\tpads\n");
            Map<String, Region> regions = new LinkedHashMap<>();
            for (Region region : atlas.getRegions()) {
                inventory.write(String.join("\t", region.name, region.page.textureFile.name(),
                    Integer.toString(region.left), Integer.toString(region.top),
                    Integer.toString(region.width), Integer.toString(region.height),
                    Float.toString(region.offsetX), Float.toString(region.offsetY),
                    Integer.toString(region.originalWidth), Integer.toString(region.originalHeight),
                    Boolean.toString(region.rotate), Arrays.toString(region.splits),
                    Arrays.toString(region.pads)));
                inventory.write('\n');
                // Mirror TextureAtlas's lookup when the atlas lists a name more than once.
                regions.put(region.name, region);
            }
            return regions;
        }
    }

    private static BufferedImage readPage(ZipFile archive, AtlasPage page) throws IOException {
        try (InputStream stream = open(archive, ATLAS_DIR + page.textureFile.name())) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() != page.width || image.getHeight() != page.height) {
                throw new IOException("Invalid upstream atlas page " + page.textureFile.name());
            }
            return image;
        }
    }

    private static BufferedImage exportRegion(BufferedImage source, Region region, Path destination)
            throws IOException {
        if (region.rotate) {
            throw new IOException("Unexpected rotation in the pinned Arc atlas: " + region.name);
        }
        BufferedImage pixels = source.getSubimage(region.left, region.top, region.width, region.height);
        if (!ImageIO.write(pixels, "PNG", destination.toFile())) {
            throw new IOException("PNG encoder unavailable");
        }
        BufferedImage saved = ImageIO.read(destination.toFile());
        if (saved == null || saved.getWidth() != region.width || saved.getHeight() != region.height
                || !Arrays.equals(argb(pixels), argb(saved))) {
            throw new IOException("PNG changed original pixels for " + region.name);
        }
        return pixels;
    }

    private static int[] argb(BufferedImage image) {
        int width = image.getWidth();
        return image.getRGB(0, 0, width, image.getHeight(), null, 0, width);
    }
}
