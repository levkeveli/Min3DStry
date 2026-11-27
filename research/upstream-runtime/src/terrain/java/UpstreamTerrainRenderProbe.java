import arc.Core;
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
import port.render.UpstreamCanvasTexture;
import port.render.UpstreamLogicFramebuffer.RasterTexture;
import port.render.WeatherNoiseTexture;

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

    float drawChatLine(byte[] text, int[] characters, float bottom) {
        return NativeTerrain.drawChatLine(Address.ofData(text), Address.ofData(characters), characters.length / 7, bottom);
    }

    void drawChatKeyboard(byte[] labels) {
        if (NativeTerrain.drawChatKeyboard(Address.ofData(labels)) == 0) {
            throw new IllegalStateException("Unable to draw the live chat keyboard");
        }
    }

    static String readSchematicName() {
        byte[] text = new byte[4001];
        byte[] hint = (Core.bundle.get("schematic.add") + " · " + Core.bundle.get("name") + "\0")
            .getBytes(StandardCharsets.UTF_8);
        System.out.println("UPSTREAM_SCHEMATIC_NAME_PROMPT");
        int length = NativeTerrain.schematicPrompt(Address.ofData(text), text.length, Address.ofData(hint), 0);
        return length > 0 && length < text.length ? new String(text, 0, length, StandardCharsets.UTF_8) : null;
    }

    static boolean confirmSchematicReplacement(String name) {
        byte[] text = new byte[5];
        byte[] hint = (Core.bundle.get("schematic.replace") + " " + name + "\0").getBytes(StandardCharsets.UTF_8);
        System.out.println("UPSTREAM_SCHEMATIC_REPLACE_PROMPT");
        return NativeTerrain.schematicPrompt(Address.ofData(text), text.length, Address.ofData(hint), 1) != 0;
    }

    static String editSchematicName(String original) {
        System.out.println("UPSTREAM_SCHEMATIC_EDIT_PROMPT field=name");
        return editText(Core.bundle.get("schematic.edit") + " · " + Core.bundle.get("name"), original, 2);
    }

    static String editSchematicDescription(String original) {
        System.out.println("UPSTREAM_SCHEMATIC_EDIT_PROMPT field=editor.description");
        return editText(Core.bundle.get("schematic.edit") + " · " + Core.bundle.get("editor.description"), original, 3);
    }

    static String readDatabaseSearch(String original) {
        return editText(Core.bundle.get("players.search"), original, 2);
    }

    private static String editText(String caption, String original, int mode) {
        byte[] text = new byte[Math.max(1000, original.length()) * 4 + 1];
        byte[] initialBytes = original.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(initialBytes, 0, text, 0, initialBytes.length);
        byte[] hint = (caption + "\0").getBytes(StandardCharsets.UTF_8);
        int length = NativeTerrain.schematicPrompt(Address.ofData(text), text.length, Address.ofData(hint), mode);
        return length >= 0 && length < text.length ? new String(text, 0, length, StandardCharsets.UTF_8) : null;
    }

    // Keep the catalog kinds in the same order as the native renderer.
    static final int ITEM_CATALOG = 0, BLOCK_CATALOG = 1, UNIT_CATALOG = 2, COMMAND_CATALOG = 3, STANCE_CATALOG = 4, SCHEMATIC_CATALOG = 5;

    int buildCatalog(byte[] labels, int[] icons, int selected, boolean previous, boolean next, int catalog) {
        int activeRows = selected >= 0 && selected < Integer.SIZE ? 1 << selected : 0;
        return buildCatalogRows(labels, icons, activeRows, previous, next, catalog);
    }

    int buildCatalogRows(byte[] labels, int[] icons, int activeRows, boolean previous, boolean next, int catalog) {
        return NativeTerrain.buildCatalog(Address.ofData(labels), Address.ofData(icons), activeRows,
            previous ? 1 : 0, next ? 1 : 0, catalog);
    }

    boolean campaignPlanetAvailable() {
        return NativeTerrain.campaignPlanetAvailable() != 0;
    }

    boolean drawCampaignPlanet(int planet, float[] matrix, float[] lighting, float[] overlay,
            int vertices, int sectorVertices, int[] surface, int[] ids, float[] planes, float[] markerMatrix, int markers) {
        return NativeTerrain.drawCampaignPlanet(planet, Address.ofData(matrix), Address.ofData(lighting),
            Address.ofData(overlay), vertices, sectorVertices, Address.ofData(surface), surface.length / 3,
            Address.ofData(ids), Address.ofData(planes), Address.ofData(markerMatrix), markers) != 0;
    }

    void releaseCampaignPlanet() {
        NativeTerrain.releaseCampaignPlanet();
    }

    float drawObjective(byte[] label, int[] characters, TextureRegion icon, float y) {
        int page = icon == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(icon);
        return NativeTerrain.drawObjective(Address.ofData(label), Address.ofData(characters), characters.length / 7, page,
            icon == null ? 0 : icon.getX(), icon == null ? 0 : icon.getY(),
            icon == null ? 0 : icon.width, icon == null ? 0 : icon.height, y, Address.ofData(stockObjectiveFont));
    }

    float drawObjectivePlain(byte[] label, int[] characters, TextureRegion icon, float y) {
        int page = icon == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(icon);
        return NativeTerrain.drawObjectivePlain(Address.ofData(label), Address.ofData(characters),
            characters.length / 7, page,
            icon == null ? 0 : icon.getX(), icon == null ? 0 : icon.getY(),
            icon == null ? 0 : icon.width, icon == null ? 0 : icon.height,
            y, Address.ofData(stockObjectiveFont));
    }

    void drawMenuHelp(byte[] labels, int count) {
        NativeTerrain.drawMenuHelp(Address.ofData(labels), count);
    }

    static int menuHelpAction(int touch, int count) {
        if (touch < 0) return -1;
        int x = touch & 0xffff;
        int offset = (touch >>> 16) - 60;
        if (x < 20 || x >= 300 || offset < 0 || offset >= count * 30 || offset % 30 >= 26) return -1;
        return offset / 30;
    }

    void hudLabel(byte[] label, int[] characters, float x, float y, float width, float height) {
        NativeTerrain.hudLabel(Address.ofData(label), Address.ofData(characters), characters.length / 7,
            x, y, width, height);
    }

    int hudSprite(TextureRegion region, int left, int top, int sourceWidth, int sourceHeight,
                  float x, float y, float width, float height, int color) {
        return NativeTerrain.hudSprite(UpstreamTerrainDrawProbe.pageIndex(region), region.getX() + left,
            region.getY() + top, sourceWidth, sourceHeight, x, y, width, height, color);
    }

    int hudPanel(float x, float y, float width, float height, int color) {
        return NativeTerrain.hudPanel(x, y, width, height, color);
    }

    float drawObjectiveAt(byte[] label, int[] characters, TextureRegion icon, float x, float y) {
        int page = icon == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(icon);
        return NativeTerrain.drawObjectiveAt(Address.ofData(label), Address.ofData(characters), characters.length / 7, page,
            icon == null ? 0 : icon.getX(), icon == null ? 0 : icon.getY(),
            icon == null ? 0 : icon.width, icon == null ? 0 : icon.height, x, y,
            Address.ofData(stockObjectiveFont));
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
    public void drawDarkness(byte[] alpha, int width, int height) {
        if (NativeTerrain.drawDarkness(Address.ofData(alpha), width, height) == 0) {
            throw new IllegalStateException("Unable to draw the stock world darkness texture");
        }
    }

    @Override
    public void finishTerrain() {
        NativeTerrain.finishTerrain();
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

    @Override
    public void drawWeatherNoise(WeatherNoiseTexture texture, float x, float y, float width, float height,
                                 float u, float v, float u2, float v2, int tint, int mix, Blending blending) {
        if (NativeTerrain.drawWeatherNoise(texture.index, x, y, width, height, u, v, u2, v2,
                tint, mix, blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro2D could not draw the stock weather noise texture");
        }
    }

    @Override
    public void drawTexturedQuad(int page, int pageWidth, int pageHeight, float[] vertices, int offset, Blending blending) {
        if (NativeTerrain.drawTexturedQuad(page, pageWidth, pageHeight, Address.ofData(vertices), offset,
                blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro3D could not draw a stock Arc textured quad");
        }
    }

    @Override
    public void drawCanvas(UpstreamCanvasTexture texture, float x, float y, float originX, float originY,
                           float width, float height, float rotation, int color, int mixColor, Blending blending) {
        if (NativeTerrain.drawCanvas(texture.id, texture.revision, texture.width, texture.height,
                Address.ofData(texture.pixels), x, y, originX, originY, width, height, rotation, color, mixColor,
                blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro2D could not draw the stock Canvas texture");
        }
    }

    @Override
    public void drawCanvasQuad(UpstreamCanvasTexture texture, float[] vertices, int offset, Blending blending) {
        if (NativeTerrain.drawCanvasQuad(texture.id, texture.revision, texture.width, texture.height,
                Address.ofData(texture.pixels), Address.ofData(vertices), offset, blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro3D could not draw the stock Canvas quad");
        }
    }

    @Override
    public void drawLogicDisplay(RasterTexture texture, float[] vertices, int offset, Blending blending) {
        if (NativeTerrain.drawLogicDisplay(texture.id, texture.revision, texture.width, texture.height,
                Address.ofData(texture.pixels), Address.ofData(vertices), offset, blendMode(blending)) == 0) {
            throw new IllegalStateException("Citro3D could not draw a stock Logic Display");
        }
    }

    @Override
    public void overlay() {
        if (Core.settings != null && !Core.settings.getBool("minimap", true)) return;
        NativeTerrain.minimapDraw(336f, 8f, 56f);
    }

    void minimapUpload(byte[] pixels, int width, int height) {
        NativeTerrain.minimapUpload(Address.ofData(pixels), width, height);
    }

    void drawMinimapAt(float x, float y, float size) {
        NativeTerrain.minimapDraw(x, y, size);
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

        @Import(name = "m3ds_terrain_draw_textured_quad")
        static native int drawTexturedQuad(int page, int pageWidth, int pageHeight, Address vertices, int offset, int blendMode);

        @Import(name = "m3ds_terrain_draw_canvas")
        static native int drawCanvas(int id, int revision, int width, int height, Address pixels,
                                     float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                                     float rotation, int color, int mixColor, int blendMode);
        @Import(name = "m3ds_terrain_draw_weather_noise")
        static native int drawWeatherNoise(int texture, float x, float y, float width, float height,
                                           float u, float v, float u2, float v2, int tint, int mix, int blendMode);

        @Import(name = "m3ds_terrain_draw_canvas_quad")
        static native int drawCanvasQuad(int id, int revision, int width, int height, Address pixels,
                                         Address vertices, int offset, int blendMode);

        @Import(name = "m3ds_terrain_draw_logic_display")
        static native int drawLogicDisplay(int id, int revision, int width, int height, Address pixels,
                                           Address vertices, int offset, int blendMode);

        @Import(name = "m3ds_terrain_finish_terrain")
        static native void finishTerrain();

        @Import(name = "m3ds_terrain_end")
        static native int end();

        @Import(name = "m3ds_terrain_build_overlay")
        static native int buildOverlay(int column, int row, int block, int rotation, int removing, int tileSize,
                                       int rotatable, int catalogOpen, int configuring, int selectingMine,
                                       int inventoryAction, int commandMode, int selectingControl, int placingSchematic);

        @Import(name = "m3ds_terrain_draw_chat_line")
        static native float drawChatLine(Address text, Address characters, int count, float bottom);

        @Import(name = "m3ds_terrain_draw_chat_keyboard")
        static native int drawChatKeyboard(Address labels);

        @Import(name = "m3ds_terrain_schematic_prompt")
        static native int schematicPrompt(Address text, int capacity, Address hint, int confirm);

        @Import(name = "m3ds_terrain_build_catalog")
        static native int buildCatalog(Address labels, Address icons, int activeRows,
                                       int previous, int next, int catalog);

        @Import(name = "m3ds_terrain_campaign_planet_available")
        static native int campaignPlanetAvailable();

        @Import(name = "m3ds_terrain_draw_campaign_planet")
        static native int drawCampaignPlanet(int planet, Address matrix, Address lighting, Address overlay,
            int vertices, int sectorVertices, Address surface, int faces,
            Address ids, Address planes, Address markerMatrix, int markers);

        @Import(name = "m3ds_terrain_release_campaign_planet")
        static native void releaseCampaignPlanet();

        @Import(name = "m3ds_terrain_draw_objective")
        static native float drawObjective(Address label, Address characters, int count,
                                          int page, int x, int y, int width, int height, float top, Address stockFont);

        @Import(name = "m3ds_terrain_draw_menu_help")
        static native void drawMenuHelp(Address labels, int count);

        @Import(name = "m3ds_terrain_draw_objective_plain")
        static native float drawObjectivePlain(Address label, Address characters, int count,
                                               int page, int x, int y, int width, int height,
                                               float top, Address stockFont);

        @Import(name = "m3ds_terrain_draw_hud_label")
        static native void hudLabel(Address label, Address characters, int count,
                                    float x, float y, float width, float height);

        @Import(name = "m3ds_terrain_draw_hud_sprite")
        static native int hudSprite(int page, int left, int top, int width, int height,
            float x, float y, float drawWidth, float drawHeight, int color);

        @Import(name = "m3ds_terrain_draw_hud_panel")
        static native int hudPanel(float x, float y, float width, float height, int color);

        @Import(name = "m3ds_terrain_draw_objective_at")
        static native float drawObjectiveAt(Address label, Address characters, int count,
                                            int page, int x, int y, int width, int height,
                                            float left, float top, Address stockFont);

        @Import(name = "m3ds_terrain_draw_world_label")
        static native int drawWorldLabel(Address label, Address characters, int count, float x, float y, int flags, float size,
                                         int horizontal, int vertical, int lines);

        @Import(name = "m3ds_terrain_draw_darkness")
        static native int drawDarkness(Address alpha, int width, int height);

        @Import(name = "m3ds_terrain_minimap_upload")
        static native void minimapUpload(Address pixels, int width, int height);

        @Import(name = "m3ds_terrain_minimap_draw")
        static native void minimapDraw(float x, float y, float size);
    }
}
