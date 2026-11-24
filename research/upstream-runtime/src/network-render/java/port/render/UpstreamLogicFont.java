package port.render;

import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.Font.Glyph;
import arc.graphics.g2d.TextureRegion;
import mindustry.ui.Fonts;
import port.render.UpstreamLogicFramebuffer.SampledTexture;

import java.io.DataInputStream;
import java.io.IOException;

public final class UpstreamLogicFont {
    public static void load(Fi source) {
        try (var in = new DataInputStream(source.read(1024))) {
            if (in.readInt() != 0x4d334c31) throw new IOException("Invalid logic font header");
            int width = in.readUnsignedShort(), height = in.readUnsignedShort();
            if (width != 256 || height != 128) throw new IOException("Invalid logic font atlas size");
            var data = new FontData();
            data.spaceXadvance = in.readFloat();
            data.lineHeight = in.readFloat();
            data.capHeight = in.readFloat();
            data.ascent = in.readFloat();
            int count = in.readUnsignedShort();
            if (count > 128) throw new IOException("Invalid logic font glyph count");
            for (int i = 0; i < count; i++) {
                var glyph = new Glyph();
                glyph.id = in.readChar();
                glyph.srcX = in.readShort();
                glyph.srcY = in.readShort();
                glyph.width = in.readShort();
                glyph.height = in.readShort();
                glyph.xoffset = in.readShort();
                glyph.yoffset = in.readShort();
                glyph.xadvance = in.readShort();
                data.setGlyph(glyph.id, glyph);
            }
            var texture = new LogicFontTexture(in, width, height);
            if (in.read() != -1) throw new IOException("Unexpected logic font data");
            Fonts.logic = new Font(data, new TextureRegion(texture), true);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read v160.8 Logic Display glyphs from " + source, error);
        }
    }

    private static final class LogicFontTexture extends Texture implements SampledTexture {
        private static final int colors = 4;
        private final int[] palette = new int[colors];
        private final byte[] indices;

        LogicFontTexture(DataInputStream in, int width, int height) throws IOException {
            this.width = width;
            this.height = height;
            int pixels = width * height;
            indices = new byte[(pixels + 3) / 4];
            int paletteSize = 0;
            for (int i = 0; i < pixels; i++) {
                int rgba = in.readInt();
                int index = 0;
                while (index < paletteSize && palette[index] != rgba) index++;
                if (index == paletteSize) {
                    if (paletteSize == colors) throw new IOException("Unexpected stock logic font color");
                    palette[paletteSize++] = rgba;
                }
                indices[i / 4] |= (byte)(index << ((i % 4) * 2));
            }
        }

        @Override
        public int pixel(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) throw new IndexOutOfBoundsException();
            int i = y * width + x;
            return palette[(indices[i / 4] >>> ((i % 4) * 2)) & 3];
        }
    }
}
