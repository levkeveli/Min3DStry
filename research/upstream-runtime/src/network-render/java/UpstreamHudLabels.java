import port.render.UpstreamLabelText;

final class UpstreamHudLabels {
    private UpstreamHudLabels() {}

    static int[] encodeLabel(UpstreamLabelText.Label text) {
        int[] encoded = new int[text.characters().size() * 7];
        int offset = 0;
        for (var glyph : text.characters()) {
            var art = glyph.art();
            encoded[offset++] = glyph.codepoint();
            encoded[offset++] = glyph.color();
            encoded[offset++] = art == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(art);
            encoded[offset++] = art == null ? 0 : art.getX();
            encoded[offset++] = art == null ? 0 : art.getY();
            encoded[offset++] = art == null ? 0 : art.width;
            encoded[offset++] = art == null ? 0 : art.height;
        }
        return encoded;
    }
}
