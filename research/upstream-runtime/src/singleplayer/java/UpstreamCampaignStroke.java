import arc.math.Mathf;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;

/** Converts one desktop pixel perpendicular to a projected line into a world-space offset. */
final class UpstreamCampaignStroke {
    private static final float HALF_WIDTH = 200f, HALF_HEIGHT = 120f;
    private final Vec3 right = new Vec3(), up = new Vec3();
    private float[] matrix;
    private float x, y;

    void project(Mat3D projection) {
        matrix = projection.val;
        right.set(matrix[0], matrix[4], matrix[8]);
        right.scl(1f / right.len2());
        up.set(matrix[1], matrix[5], matrix[9]);
        up.scl(1f / up.len2());
    }

    float measure(Vec3 start, Vec3 end) {
        float startW = w(start), endW = w(end);
        float dx = HALF_WIDTH * (clip(0, end) / endW - clip(0, start) / startW);
        float dy = HALF_HEIGHT * (clip(1, end) / endW - clip(1, start) / startW);
        float length = Mathf.sqrt(dx * dx + dy * dy);
        if (length >= 0.001f) {
            x = -dy / (2f * HALF_WIDTH * length);
            y = dx / (2f * HALF_HEIGHT * length);
        }
        return length;
    }

    void offset(Vec3 out, float w) {
        out.set(right).scl(x * w).mulAdd(up, y * w);
    }

    float w(Vec3 point) {
        return clip(3, point);
    }

    private float clip(int row, Vec3 point) {
        return matrix[row] * point.x + matrix[row + 4] * point.y
            + matrix[row + 8] * point.z + matrix[row + 12];
    }
}
