package port.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockFiles;
import arc.struct.IntMap;
import java.util.ArrayList;
import java.util.List;
import mindustry.core.UI;
import mindustry.ui.Fonts;

/** Uses Arc's original markup rules and the official content glyph table. */
public final class UpstreamLabelText {
    public record Character(int codepoint, int color, TextureRegion art) {}
    public record Label(String plain, List<Character> characters) {}

    private static final IntMap<TextureRegion> content = new IntMap<>();
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
        if (!table.exists()) return;
        var previousFiles = Core.files;
        try {
            Core.files = new MockFiles() {
                @Override
                public Fi internal(String path) {
                    return table;
                }
            };
            Fonts.loadContentIconsHeadless();
        } finally {
            Core.files = previousFiles;
        }
        content.clear();
        for (String line : table.readString("UTF-8").split("\n")) {
            int equals = line.indexOf('='), bar = line.indexOf('|', equals + 1);
            if (equals < 1 || bar < 0) continue;
            var region = Core.atlas.find(line.substring(bar + 1).strip());
            if (region.found()) content.put(Integer.parseInt(line.substring(0, equals)), region);
        }
        lastText = null;
    }

    public static Label parse(String text) {
        if (text.equals(lastText)) return lastLabel;
        // Dummy metrics expose glyphs and colors without an OpenGL font. Native
        // FreeType metrics determine their final positions.
        var layout = GlyphLayout.obtain();
        var plain = new StringBuilder();
        var characters = new ArrayList<Character>();
        int line = 0;
        try {
            layout.setText(markup, text);
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
}
