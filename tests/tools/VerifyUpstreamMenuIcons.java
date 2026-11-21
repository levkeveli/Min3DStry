import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Checks the decoded 3DS textures against the lossless exports from the pinned Arc atlas. */
class VerifyUpstreamMenuIcons {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var manifest = Files.readAllLines(root.resolve("menu-icons.tsv"));
        int blocks = 0, items = 0;
        long pixels = 0;
        for (String line : manifest.subList(1, manifest.size())) {
            String[] fields = line.split("\t");
            String name = fields[0];
            if (name.startsWith("block-")) blocks++;
            else if (name.startsWith("item-")) items++;
            else throw new AssertionError("Unknown content kind: " + name);
            var image = ImageIO.read(root.resolve("png/" + name + ".png").toFile());
            byte[] bytes = Files.readAllBytes(root.resolve("catalog/" + name + ".t3x"));
            var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int dimensions = data.get(2) & 255;
            int width = 8 << (dimensions & 7), height = 8 << ((dimensions >> 3) & 7);
            int sw = data.getShort(5), sh = data.getShort(7);
            if (data.getShort(0) != 1 || data.get(3) != 0 || data.get(4) != 0
                    || width > 128 || height > 128 || bytes.length != 21 + width * height * 4
                    || data.getInt(17) != width * height * 1024
                    || data.getShort(9) != 0 || data.getShort(11) != 1024
                    || data.getShort(13) * width != sw * 1024 || (1024 - data.getShort(15)) * height != sh * 1024
                    || sw != image.getWidth() || sh != image.getHeight()) {
                throw new AssertionError("Invalid RGBA8 subtexture: " + name);
            }
            for (int index = 0; index < width * height; index++) {
                int tile = index / 64;
                int x = tile % (width / 8) * 8 + (index & 1) + ((index >> 1) & 2) + ((index >> 2) & 4);
                int y = tile / (width / 8) * 8 + ((index >> 1) & 1) + ((index >> 2) & 2) + ((index >> 3) & 4);
                int stored = data.getInt(21 + index * 4);
                int argb = (stored >>> 8) | (stored << 24);
                if (argb != (x < sw && y < sh ? image.getRGB(x, y) : 0)) {
                    throw new AssertionError(name + " changed texel " + x + "," + y);
                }
            }
            pixels += (long) sw * sh;
        }
        if (blocks != 425 || items != 22) throw new AssertionError("Unexpected v160.8 icon inventory");
        System.out.printf("Verified %d block and %d item icons, %,d original texels%n", blocks, items, pixels);
    }
}
