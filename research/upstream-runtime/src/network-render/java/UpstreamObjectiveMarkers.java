import arc.graphics.g2d.Draw;
import mindustry.game.MapObjectives.ObjectiveMarker;
import mindustry.game.MapObjectives.ShapeTextMarker;
import mindustry.game.MapObjectives.TextMarker;
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
                    for (var marker : objective.markers) drawOne(marker, displayScale);
                });
            } finally {
                Draw.sort(false);
                Draw.reset();
            }
        } finally {
            UpstreamWorldLabel.end();
        }
    }

    private static void drawOne(ObjectiveMarker marker, float displayScale) {
        if (marker.world == -1) return;
        float scale = marker.autoscale ? 4f / displayScale : 1f;
        if (marker instanceof TextMarker text) text.draw(scale);
        if (marker instanceof ShapeTextMarker shape) shape.draw(scale);
    }
}
