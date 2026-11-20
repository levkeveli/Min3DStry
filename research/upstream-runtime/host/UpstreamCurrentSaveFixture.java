import arc.files.Fi;
import java.io.IOException;
import mindustry.Vars;
import mindustry.game.MapObjectives.PointMarker;
import mindustry.io.SaveIO;
import mindustry.mod.Mods;

public final class UpstreamCurrentSaveFixture {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected paths to the original Glacier map and new save");
        }
        var save = new Fi(args[1]);
        if (save.exists()) {
            throw new IllegalArgumentException("Save fixture path already exists: " + save);
        }
        UpstreamWorldLoadProbe.main(new String[] {args[0]});
        Vars.mods = new Mods();
        Vars.state.markers.add(37, new PointMarker(80, 152));
        SaveIO.write(save);
    }
}
