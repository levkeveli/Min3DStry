import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Interp;
import arc.math.Mathf;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.WorldLabel;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import port.render.UpstreamWorldLabel;

final class UpstreamWorldPings {
    private static final float DURATION = 20f * 60f;
    private final Color foreground = new Color(), border = new Color(), text = new Color();

    void draw(float delta, float displayScale, UpstreamWorldLabel.Target labels) {
        if (!Core.settings.getBool("showpings", true) || Vars.player == null) return;
        float depth = Draw.z();
        UpstreamWorldLabel.begin(labels, displayScale);
        try {
            for (Player player : Groups.player) {
                if (player.pingTime <= 0f || player.name == null
                        || (!Vars.state.rules.showOtherTeamPings && player.team() != Vars.player.team())) continue;
                drawPing(player, delta, displayScale);
            }
        } finally {
            UpstreamWorldLabel.end();
            Draw.reset();
            Draw.z(depth);
        }
    }

    // Stock PlayerComp.drawPing() animation and shape sizes on the handheld world scale.
    private void drawPing(Player player, float delta, float displayScale) {
        float alpha = Math.min(
            Interp.pow5Out.apply(Mathf.clamp(Mathf.map(player.pingTime, 1f / 20f, 0f, 1f, 0f))),
            Interp.pow5Out.apply(Mathf.map(player.pingTime, 1f, 0.98f, 0f, 1f)));
        player.pingTime = Math.max(0, player.pingTime - delta / DURATION);
        float x = player.pingX, y = player.pingY;
        if (alpha <= 0 || !Float.isFinite(x) || !Float.isFinite(y)) return;

        foreground.set(player.color).a(alpha);
        border.set(Color.darkGray).mul(player.color).a(alpha);
        float size = 4f / displayScale;
        float hover = Mathf.absin(5f, 1f);
        float scaling = 1f + Mathf.clamp(Interp.pow5In.apply(Mathf.map(player.pingTime, 1f, 0.96f, 1f, 0f))) * 3f;
        Draw.z(Layer.playerName);
        Drawf.square(x, y, 2f * scaling * size, 45f, foreground, border, size);
        Drawf.fillPoly(x, y + (9f + hover) * size, 3, 3f * size, -90f, foreground, border, size);
        if (player.pingText != null) {
            label(player.name, x, y + (20f + hover) * size, 0.7f * size, foreground);
            label(player.pingText, x, y + (16f + hover) * size, size, text.set(1f, 1f, 1f, alpha));
        } else {
            label(player.name, x, y + (16f + hover) * size, size, foreground);
        }
    }

    private static void label(String text, float x, float y, float size, Color color) {
        UpstreamWorldLabel.drawAt("[#" + color + "]" + text + "[]", x, y, Layer.playerName,
            WorldLabel.flagOutline, size, Align.center, Align.center);
    }
}
