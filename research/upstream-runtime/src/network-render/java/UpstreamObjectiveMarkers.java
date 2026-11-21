import arc.graphics.g2d.Draw;
import mindustry.Vars;
import mindustry.game.MapObjectives.ObjectiveMarker;
import mindustry.game.MapObjectives.PosMarker;
import mindustry.game.MapObjectives.TextureMarker;
import mindustry.game.Rules;
import port.render.UpstreamWorldLabel;

final class UpstreamObjectiveMarkers {
    static void draw(Rules rules, float displayScale, UpstreamWorldLabel.Target text) {
        UpstreamWorldLabel.begin(text, displayScale);
        try {
            Draw.sort(true);
            try {
                // Same active set and world flag as the stock Renderer world pass.
                rules.objectives.eachRunning(objective -> {
                    for (var marker : objective.markers) {
                        if (marker.world != -1) drawOne(marker, displayScale);
                    }
                });
                for (var marker : Vars.state.markers.worldMarkers) drawOne(marker, displayScale);
            } finally {
                Draw.sort(false);
                Draw.reset();
            }
        } finally {
            UpstreamWorldLabel.end();
        }
    }

    private static void drawOne(ObjectiveMarker marker, float displayScale) {
        // Texture and quad markers can reference dynamic display targets that
        // the current Arc bridge cannot sample.
        if (marker instanceof PosMarker && !(marker instanceof TextureMarker)) {
            marker.draw(marker.autoscale ? 4f / displayScale : 1f);
        }
    }
}
