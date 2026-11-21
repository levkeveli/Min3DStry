import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/** Reads every device tile back against the original full-resolution atlas images. */
class VerifyUpstreamWorldAtlas {
    private static final int TILE = 128;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        var rows = Files.readAllLines(root.resolve("world-atlas.tsv"));
        int tiles = 0;
        try (var archive = new ZipFile(args[0])) {
            for (int index = 1; index < rows.size(); index++) {
                String[] row = rows.get(index).split("\t");
                if (Integer.parseInt(row[0]) != index - 1) throw new AssertionError("Atlas page order changed");
                var entry = archive.getEntry("assets/sprites/" + row[1]);
                try (var input = archive.getInputStream(entry)) {
                    var source = ImageIO.read(input);
                    if (source.getWidth() != Integer.parseInt(row[2]) || source.getHeight() != Integer.parseInt(row[3])) {
                        throw new AssertionError("Source dimensions differ for " + row[1]);
                    }
                    for (int y = 0; y < source.getHeight(); y += TILE) {
                        for (int x = 0; x < source.getWidth(); x += TILE) {
                            String file = row[0] + "-" + (x / TILE) + "-" + (y / TILE) + ".t3x";
                            byte[] bytes = Files.readAllBytes(root.resolve("world-atlas").resolve(file));
                            var sheet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                            if (bytes.length != 21 + TILE * TILE * 4 || sheet.getShort(0) != 1
                                    || sheet.get(2) != (4 | (4 << 3)) || sheet.get(3) != 0 || sheet.get(4) != 0
                                    || sheet.getShort(5) != TILE || sheet.getShort(7) != TILE || sheet.getShort(9) != 0
                                    || sheet.getShort(11) != 1024 || sheet.getShort(13) != 1024 || sheet.getShort(15) != 0
                                    || sheet.getInt(17) != TILE * TILE * 1024) {
                                throw new AssertionError("Invalid RGBA8 tile " + file);
                            }
                            for (int texel = 0; texel < TILE * TILE; texel++) {
                                int block = texel / 64;
                                int dx = block % 16 * 8 + (texel & 1) + ((texel >> 1) & 2) + ((texel >> 2) & 4);
                                int dy = block / 16 * 8 + ((texel >> 1) & 1) + ((texel >> 2) & 2) + ((texel >> 3) & 4);
                                int raw = sheet.getInt(21 + texel * 4);
                                if (((raw >>> 8) | (raw << 24)) != source.getRGB(x + dx, y + dy)) {
                                    throw new AssertionError("Changed atlas texel in " + file + " at " + dx + "," + dy);
                                }
                            }
                            tiles++;
                        }
                    }
                }
            }
        }
        if (rows.size() != 5 || tiles != 2048) throw new AssertionError("Pinned atlas page inventory changed");
        System.out.printf("Verified %d pages, %d tiles and %d original RGBA texels%n",
            rows.size() - 1, tiles, tiles * TILE * TILE);
    }
}
