import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.util.Align;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import org.teavm.interop.Import;
import org.teavm.interop.Address;
import org.teavm.interop.c.Include;

public final class UpstreamTerrainRenderProbe implements UpstreamTerrainDrawProbe.TerrainTarget {
    static final int PALETTE_LABEL_COUNT = 15;

    private final boolean reportFrame;
    private final int[] stockObjectiveFont = new int[1];

    UpstreamTerrainRenderProbe(boolean reportFrame) {
        this.reportFrame = reportFrame;
    }

    public static void main(String[] args) throws IOException {
        UpstreamTerrainDrawProbe.capture(args, new UpstreamTerrainRenderProbe(true));
    }

    void initialize() {
        int result = NativeTerrain.initialize();
        if (result != 1) throw new IllegalStateException("Unable to initialize Citro2D terrain: " + result);
    }

    int mountAssets() throws IOException {
        if (NativeTerrain.mountAssets() == 0) throw new IOException("Unable to mount the original game assets");
        return NativeTerrain.systemLanguage();
    }

    boolean loadStockFont(boolean japanese) {
        return NativeTerrain.loadStockFont(japanese ? 1 : 0) != 0;
    }

    void buildLabels(byte[] labels) {
        if (NativeTerrain.buildLabels(Address.ofData(labels)) == 0) {
            throw new IllegalStateException("Unable to create the Citro2D label buffer");
        }
    }

    void buildOverlay(int column, int row, int block, int rotation,
                      boolean removing, boolean rotatable, boolean catalogOpen, boolean configuring,
                      boolean selectingMine, int inventoryAction, int commandMode, boolean selectingControl,
                      boolean placingSchematic) {
        if (NativeTerrain.buildOverlay(column, row, block, rotation, removing ? 1 : 0, Vars.tilesize,
                rotatable ? 1 : 0, catalogOpen ? 1 : 0, configuring ? 1 : 0, selectingMine ? 1 : 0,
                inventoryAction, commandMode, selectingControl ? 1 : 0, placingSchematic ? 1 : 0) == 0) {
            throw new IllegalStateException("Unable to draw the Citro2D build palette");
        }
    }

    // Keep the catalog kinds in the same order as the native renderer.
    static final int ITEM_CATALOG = 0, BLOCK_CATALOG = 1, UNIT_CATALOG = 2, COMMAND_CATALOG = 3, STANCE_CATALOG = 4;

    int buildCatalog(byte[] labels, int[] icons, int selected, boolean previous, boolean next, int catalog) {
        int activeRows = selected >= 0 && selected < Integer.SIZE ? 1 << selected : 0;
        return buildCatalogRows(labels, icons, activeRows, previous, next, catalog);
    }

    int buildCatalogRows(byte[] labels, int[] icons, int activeRows, boolean previous, boolean next, int catalog) {
        return NativeTerrain.buildCatalog(Address.ofData(labels), Address.ofData(icons), activeRows,
            previous ? 1 : 0, next ? 1 : 0, catalog);
    }

    float drawObjective(byte[] label, int[] characters, TextureRegion icon, float y) {
        int page = icon == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(icon);
        return NativeTerrain.drawObjective(Address.ofData(label), Address.ofData(characters), characters.length / 7, page,
            icon == null ? 0 : icon.getX(), icon == null ? 0 : icon.getY(),
            icon == null ? 0 : icon.width, icon == null ? 0 : icon.height, y, Address.ofData(stockObjectiveFont));
    }

    boolean drewStockObjectiveFont() {
        return stockObjectiveFont[0] != 0;
    }

    int drawWorldLabel(String label, int[] characters, float x, float y, int flags, float size, int align, int lineAlign) {
        int horizontal = Align.isLeft(align) ? -1 : Align.isRight(align) ? 1 : 0;
        int vertical = Align.isTop(align) ? -1 : Align.isBottom(align) ? 1 : 0;
        int lines = Align.isLeft(lineAlign) ? -1 : Align.isRight(lineAlign) ? 1 : 0;
        byte[] text = (label + "\0").getBytes(StandardCharsets.UTF_8);
        return NativeTerrain.drawWorldLabel(Address.ofData(text), Address.ofData(characters), characters.length / 7,
            x, y, flags, size, horizontal, vertical, lines);
    }

    @Override
    public void begin(int left, int bottom, int width, int height) {
        int result = NativeTerrain.begin(left, bottom, width, height, Vars.tilesize);
        if (result != 1) {
            throw new IllegalStateException("Unable to start Citro2D terrain frame: " + result);
        }
    }

    @Override
    public void draw(int page, int left, int top, int width, int height,
                     float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        if (NativeTerrain.draw(page, left, top, width, height, x, y, originX, originY,
                drawWidth, drawHeight, rotation, color, mixColor, blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro2D could not draw an upstream terrain region: "
                + page + "," + left + "," + top + "+" + width + "x" + height);
        }
    }

    @Override
    public void drawUnitBuild(int page, int left, int top, int width, int height,
                              float x, float y, float drawWidth, float drawHeight, float rotation,
                              float progress, Color tint, float alpha, float phase, int vertexColor) {
        if (NativeTerrain.drawUnitBuild(page, left, top, width, height, x, y, drawWidth, drawHeight,
                rotation, progress, tint.r, tint.g, tint.b, alpha, phase, vertexColor) == 0) {
            throw new IllegalStateException("Citro2D could not draw stock unit construction");
        }
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        if (NativeTerrain.drawSolidQuad(Address.ofData(vertices), offset, blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro2D could not draw a stock Arc solid quad");
        }
    }

    private static int blendMode(Blending blending) {
        if (blending == Blending.normal) return 0;
        if (blending == Blending.additive) return 1;
        if (blending == Blending.disabled) return 2;
        throw new IllegalArgumentException("Unsupported Arc sprite blending");
    }

    @Override
    public void end() {
        int draws = NativeTerrain.end();
        if (draws == 0) throw new IllegalStateException("Citro2D terrain frame has no draws");
        if (reportFrame) System.out.println("UPSTREAM_TERRAIN_FRAME draws=" + draws);
    }

    @Include(value = "m3ds_terrain_renderer.h", isSystem = true)
    private static final class NativeTerrain {
        @Import(name = "m3ds_terrain_initialize")
        static native int initialize();

        @Import(name = "m3ds_terrain_mount_assets")
        static native int mountAssets();

        @Import(name = "m3ds_terrain_load_stock_font")
        static native int loadStockFont(int japanese);

        @Import(name = "m3ds_terrain_system_language")
        static native int systemLanguage();

        @Import(name = "m3ds_terrain_build_labels")
        static native int buildLabels(Address labels);

        @Import(name = "m3ds_terrain_begin")
        static native int begin(int left, int bottom, int width, int height, int tileSize);

        @Import(name = "m3ds_terrain_draw_unit_build")
        static native int drawUnitBuild(int page, int left, int top, int width, int height,
                                        float x, float y, float drawWidth, float drawHeight, float rotation,
                                        float progress, float r, float g, float b, float alpha, float phase,
                                        int vertexColor);

        @Import(name = "m3ds_terrain_draw")
        static native int draw(int page, int left, int top, int width, int height,
                               float x, float y, float originX, float originY, float drawWidth,
                               float drawHeight, float rotation, int color, int mixColor, int blendMode);

        @Import(name = "m3ds_terrain_draw_solid_quad")
        static native int drawSolidQuad(Address vertices, int offset, int blendMode);

        @Import(name = "m3ds_terrain_end")
        static native int end();

        @Import(name = "m3ds_terrain_build_overlay")
        static native int buildOverlay(int column, int row, int block, int rotation, int removing, int tileSize,
                                       int rotatable, int catalogOpen, int configuring, int selectingMine,
                                       int inventoryAction, int commandMode, int selectingControl, int placingSchematic);

        @Import(name = "m3ds_terrain_build_catalog")
        static native int buildCatalog(Address labels, Address icons, int activeRows,
                                       int previous, int next, int catalog);

        @Import(name = "m3ds_terrain_draw_objective")
        static native float drawObjective(Address label, Address characters, int count,
                                          int page, int x, int y, int width, int height, float top, Address stockFont);

        @Import(name = "m3ds_terrain_draw_world_label")
        static native int drawWorldLabel(Address label, Address characters, int count, float x, float y, int flags, float size,
                                         int horizontal, int vertical, int lines);
    }
}
