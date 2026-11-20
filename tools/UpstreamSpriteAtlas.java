import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.AtlasPage;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData.Region;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

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
            // Keep only one decoded upstream page in memory at a time.
            Path sprites = Files.createDirectory(output.resolve("png"));
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
                    exportRegion(source, region, sprites);
                    exported++;
                }
            }
            System.out.printf("Upstream atlas: %d pages, %d regions, %d exported%n",
                atlas.getPages().size, atlas.getRegions().size, exported);
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

    private static void exportRegion(BufferedImage source, Region region, Path sprites)
            throws IOException {
        if (region.rotate) {
            throw new IOException("Unexpected rotation in the pinned Arc atlas: " + region.name);
        }
        Path destination = sprites.resolve(region.name + ".png");
        BufferedImage pixels = source.getSubimage(region.left, region.top, region.width, region.height);
        if (!ImageIO.write(pixels, "PNG", destination.toFile())) {
            throw new IOException("PNG encoder unavailable");
        }
        BufferedImage saved = ImageIO.read(destination.toFile());
        if (saved == null || saved.getWidth() != region.width || saved.getHeight() != region.height
                || !Arrays.equals(argb(pixels), argb(saved))) {
            throw new IOException("PNG changed original pixels for " + region.name);
        }
    }

    private static int[] argb(BufferedImage image) {
        int width = image.getWidth();
        return image.getRGB(0, 0, width, image.getHeight(), null, 0, width);
    }
}
