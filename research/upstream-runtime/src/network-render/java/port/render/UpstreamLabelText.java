package port.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.TextureRegion;
import arc.struct.IntMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.core.UI;

/** Uses Arc's original markup rules and the official content glyph table. */
public final class UpstreamLabelText {
    public record Character(int codepoint, int color, TextureRegion art) {}
    public record Label(String plain, List<Character> characters) {}

    private static Fi iconTable = new Fi("/romfs/icons/icons.properties");
    private static final Font markup = new Font(new Font.FontData() {
        {
            markupEnabled = true;
            down = -1;
            blankLineScale = 1;
        }

        @Override
        public Font.Glyph getGlyph(char ch) {
            var glyph = new Font.Glyph();
            glyph.id = ch;
            glyph.xadvance = 1;
            glyph.fixedWidth = true;
            return glyph;
        }
    }, new TextureRegion(), false);
    private static String lastText;
    private static Label lastLabel;

    static {
        UI.loadColors();
    }

    public static void loadIcons(Fi table) {
        iconTable = table;
        lastText = null;
    }

    public static Label parse(String text) {
        if (text.equals(lastText)) return lastLabel;
        var content = new IntMap<TextureRegion>();
        String resolved = resolveContent(text, content);
        // Dummy metrics expose glyphs and colors without an OpenGL font. Native
        // FreeType metrics determine their final positions.
        var layout = GlyphLayout.obtain();
        var plain = new StringBuilder();
        var characters = new ArrayList<Character>();
        int line = 0;
        try {
            layout.setText(markup, resolved);
            for (var run : layout.runs) {
                while (line < -(int)run.y) {
                    plain.append('\n');
                    characters.add(new Character('\n', -1, null));
                    line++;
                }
                int color = Integer.reverseBytes(run.color.rgba8888());
                for (var glyph : run.glyphs) {
                    characters.add(new Character(glyph.id, color, content.get(glyph.id)));
                    if (glyph.id < 0xe000 || glyph.id > 0xf8ff) plain.append((char)glyph.id);
                }
            }
        } finally {
            layout.free();
        }
        lastText = text;
        lastLabel = new Label(plain.toString(), characters);
        return lastLabel;
    }

    private static String resolveContent(String text, IntMap<TextureRegion> content) {
        // The desktop registers the whole content font up front. On ARM11
        // keep only the regions referenced by this label.
        int colon = text.indexOf(':');
        boolean hasToken = colon >= 0 && text.indexOf(':', colon + 1) >= 0;
        for (int i = 0; !hasToken && i < text.length(); ++i) {
            char ch = text.charAt(i);
            hasToken = ch >= 0xe000 && ch <= 0xf8ff;
        }
        if (!hasToken) return text;
        if (!iconTable.exists()) return text;
        try (var reader = iconTable.reader(512)) {
            for (String line; (line = reader.readLine()) != null;) {
                int equals = line.indexOf('='), bar = line.indexOf('|', equals + 1);
                if (equals < 1 || bar < 0) continue;
                String token = ":" + line.substring(equals + 1, bar) + ":";
                int code = Integer.parseInt(line.substring(0, equals));
                if (text.indexOf(code) < 0 && !text.contains(token)) continue;
                var region = Core.atlas.find(line.substring(bar + 1).strip());
                if (!region.found()) continue;
                content.put(code, region);
                text = text.replace(token, String.valueOf((char)code));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read the original content icon table", error);
        }
        return text;
    }
}
