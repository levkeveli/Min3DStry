package mindustry.editor;

import arc.files.Fi;
import java.io.IOException;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.io.MapIO;
import mindustry.world.Block;

/** Platform boundary for the pinned editor. The 3DS viewport draws the world directly. */
public final class Arm11MapEditor extends MapEditor {
    public Arm11MapEditor() {
        renderer = new DirectViewport();
        Vars.editor = this;
        Vars.state.set(State.menu);
    }

    public void stroke(int x, int y, Block block, boolean erase) {
        if (tile(x, y) == null) return;
        drawBlock = block;
        (erase ? EditorTool.eraser : EditorTool.pencil).touched(x, y);
        flushOp();
    }

    public void export(Fi file, String name) throws IOException {
        if (file.exists()) throw new IOException("Map already exists: " + file.name());
        tags.put("name", name);
        MapIO.writeMap(file, createMap(file));
    }

    private static final class DirectViewport extends EditorRenderer {
        @Override public void resize(int width, int height) {}
        @Override void updateStatic(int x, int y) {}
        @Override void updateBlock(int x, int y) {}
        @Override void updateBlock(mindustry.world.Tile tile) {}
    }
}
