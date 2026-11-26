import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.Arm11MapEditor;
import mindustry.io.MapIO;

/** Exercise the original editor and the output of its stock map writer. */
public final class UpstreamStockMapEditorReference {
    public static void main(String[] args) throws Exception {
        Fi source = new Fi(args[0]);
        Fi exported = new Fi(args[1]);
        var session = UpstreamSinglePlayerSession.create(exported.parent(), "en");
        try {
            var editor = new Arm11MapEditor();
            editor.beginEdit(32, 32);
            if (editor.tile(10, 10).floor() != Blocks.stone) throw new AssertionError("New map floor");
            editor.stroke(10, 10, Blocks.sand, false);
            if (editor.tile(10, 10).floor() != Blocks.sand || !editor.canUndo()) throw new AssertionError("Stock brush");
            editor.undo();
            if (editor.tile(10, 10).floor() != Blocks.stone || !editor.canRedo()) throw new AssertionError("Undo");
            editor.redo();
            if (editor.tile(10, 10).floor() != Blocks.sand) throw new AssertionError("Redo");
            editor.stroke(10, 10, Blocks.stoneWall, false);
            if (editor.tile(10, 10).block() != Blocks.stoneWall) throw new AssertionError("Place wall");
            editor.stroke(10, 10, Blocks.air, true);
            if (editor.tile(10, 10).block() != Blocks.air) throw new AssertionError("Erase wall");

            var map = MapIO.createMap(source, true);
            editor.beginEdit(map);
            if (editor.width() != map.width || editor.height() != map.height) throw new AssertionError("Open map");
            int x = map.width / 2, y = map.height / 2;
            var original = editor.tile(x, y).floor();
            var replacement = original == Blocks.sand ? Blocks.stone : Blocks.sand;
            editor.stroke(x, y, replacement, false);
            if (editor.tile(x, y).floor() != replacement) throw new AssertionError("Edit world");
            editor.export(exported, "3DS edited " + map.name());
            var readback = MapIO.createMap(exported, true);
            if (!readback.name().equals("3DS edited " + map.name())) throw new AssertionError("Save metadata");
            editor.beginEdit(readback);
            if (Vars.world.tile(x, y).floor() != replacement) throw new AssertionError("Save tiles");
            System.out.println("UPSTREAM_STOCK_MAP_EDITOR_REFERENCE width=" + map.width + " height=" + map.height
                + " x=" + x + " y=" + y + " floor=" + replacement.name + " export=ok");
        } finally {
            session.shutdown();
        }
    }
}
