import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;

/** Draws the stock PlanetGrid edges with the cursor falloff from planetgrid.frag. */
final class UpstreamCampaignGrid {
    private static final float HALF_WIDTH = 200f, HALF_HEIGHT = 120f;
    private final Vec3 start = new Vec3(), end = new Vec3();
    private final Vec3 point = new Vec3(), offset = new Vec3();
    private final Vec3 right = new Vec3(), up = new Vec3();
    private final Vec3 previous = new Vec3(), previousOffset = new Vec3();
    private float previousFade;

    int append(Planet planet, Mat3D projection, Vec3 cursor, FloatSeq vertices) {
        float[] matrix = projection.val;
        right.set(matrix[0], matrix[4], matrix[8]);
        right.scl(1f / right.len2());
        up.set(matrix[1], matrix[5], matrix[9]);
        up.scl(1f / up.len2());
        float radius = PlanetRenderer.outlineRad * planet.radius;
        Color color = PlanetRenderer.outlineColor;
        int visible = 0;
        for (var edge : planet.grid.edges) {
            start.set(edge.corners[0].v).scl(radius);
            end.set(edge.corners[1].v).scl(radius);
            // The distance function is 1-Lipschitz, so these edges cannot contribute.
            if (start.dst(cursor) >= 1f + start.dst(end)) continue;

            float startW = clipW(matrix, start), endW = clipW(matrix, end);
            float dx = HALF_WIDTH * (clipX(matrix, end) / endW - clipX(matrix, start) / startW);
            float dy = HALF_HEIGHT * (clipY(matrix, end) / endW - clipY(matrix, start) / startW);
            float length = Mathf.sqrt(dx * dx + dy * dy);
            if (length < 0.001f) continue;

            // PICA triangles cover the same one-pixel strip as the desktop GL line.
            float ndcX = -dy / (2f * HALF_WIDTH * length);
            float ndcY = dx / (2f * HALF_HEIGHT * length);
            previous.set(start);
            lineOffset(previousOffset, ndcX, ndcY, startW);
            previousFade = fade(start, cursor);

            // Sample the stock fragment falloff along each edge. GPU interpolation
            // between samples avoids large color steps on zoomed-in sectors.
            int segments = Mathf.ceil(length / 3f);
            boolean emitted = false;
            for (int step = 1; step <= segments; step++) {
                float t = (float)step / segments;
                point.set(start).lerp(end, t);
                lineOffset(offset, ndcX, ndcY, Mathf.lerp(startW, endW, t));
                float pointFade = fade(point, cursor);
                if (previousFade > 0f || pointFade > 0f) {
                    vertex(vertices, previous, previousOffset, 1, color, previousFade);
                    vertex(vertices, previous, previousOffset, -1, color, previousFade);
                    vertex(vertices, point, offset, -1, color, pointFade);
                    vertex(vertices, previous, previousOffset, 1, color, previousFade);
                    vertex(vertices, point, offset, -1, color, pointFade);
                    vertex(vertices, point, offset, 1, color, pointFade);
                    emitted = true;
                }
                previous.set(point);
                previousOffset.set(offset);
                previousFade = pointFade;
            }
            if (emitted) visible++;
        }
        return visible;
    }

    static float fade(Vec3 point, Vec3 cursor) {
        return Math.max(0f, 1f - point.dst(cursor));
    }

    private void lineOffset(Vec3 out, float x, float y, float w) {
        out.set(right).scl(x * w).mulAdd(up, y * w);
    }

    private static float clipX(float[] matrix, Vec3 point) {
        return matrix[0] * point.x + matrix[4] * point.y + matrix[8] * point.z + matrix[12];
    }

    private static float clipY(float[] matrix, Vec3 point) {
        return matrix[1] * point.x + matrix[5] * point.y + matrix[9] * point.z + matrix[13];
    }

    private static float clipW(float[] matrix, Vec3 point) {
        return matrix[3] * point.x + matrix[7] * point.y + matrix[11] * point.z + matrix[15];
    }

    private static void vertex(FloatSeq vertices, Vec3 position, Vec3 perpendicular, int side, Color color, float fade) {
        vertices.add(position.x + perpendicular.x * side, position.y + perpendicular.y * side,
            position.z + perpendicular.z * side, color.r * fade);
        vertices.add(color.g * fade, color.b * fade, color.a * fade);
    }
}
