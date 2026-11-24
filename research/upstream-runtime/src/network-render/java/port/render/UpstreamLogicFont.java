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
import java.io.EOFException;

public final class UpstreamLogicFont {
    public static void load(Fi source) {
        try (var in = new DataInputStream(source.read(8192))) {
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
            int pixelOffset = 26 + count * 16;
            if (source.length() != pixelOffset + width * height * Integer.BYTES) {
                throw new IOException("Unexpected logic font data");
            }
            var texture = new LogicFontTexture(source, width, height, pixelOffset);
            Fonts.logic = new Font(data, new TextureRegion(texture), true);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read v160.8 Logic Display glyphs from " + source, error);
        }
    }

    private static final class LogicFontTexture extends Texture implements SampledTexture {
        private final Fi source;
        private final int pixelOffset;
        private int[] row;
        private int cachedY = -1;

        LogicFontTexture(Fi source, int width, int height, int pixelOffset) {
            this.source = source;
            this.width = width;
            this.height = height;
            this.pixelOffset = pixelOffset;
        }

        @Override
        public int pixel(int x, int y) {
            if (y != cachedY) {
                if (row == null) row = new int[width];
                try (var in = new DataInputStream(source.read(256))) {
                    int remaining = pixelOffset + y * width * Integer.BYTES;
                    while (remaining > 0) {
                        int skipped = in.skipBytes(remaining);
                        if (skipped == 0) {
                            if (in.read() == -1) throw new EOFException();
                            skipped = 1;
                        }
                        remaining -= skipped;
                    }
                    for (int index = 0; index < width; index++) row[index] = in.readInt();
                    cachedY = y;
                } catch (IOException error) {
                    throw new IllegalStateException("Could not read stock logic font row " + y, error);
                }
            }
            return row[x];
        }
    }
}
