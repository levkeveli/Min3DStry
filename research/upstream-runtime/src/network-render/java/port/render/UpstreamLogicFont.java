package port.render;

import arc.files.Fi;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.Font.Glyph;
import arc.graphics.g2d.TextureRegion;
import mindustry.ui.Fonts;
import port.render.UpstreamLogicFramebuffer.RasterTexture;

import java.io.DataInputStream;
import java.io.IOException;

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
            var texture = new RasterTexture(width, height);
            for (int i = 0; i < texture.pixels.length; i++) texture.pixels[i] = in.readInt();
            if (in.read() != -1) throw new IOException("Unexpected logic font data");
            Fonts.logic = new Font(data, new TextureRegion(texture), true);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read v160.8 Logic Display glyphs from " + source, error);
        }
    }
}
