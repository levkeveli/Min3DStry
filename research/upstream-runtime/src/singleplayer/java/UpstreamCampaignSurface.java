import arc.files.Fi;
import arc.graphics.Color;
import arc.math.geom.Vec3;
import mindustry.type.Planet;

import java.io.DataInputStream;
import java.io.IOException;

/** Recolors the stock mesh from the same generator samples after campaign state changes. */
final class UpstreamCampaignSurface {
    private static final int[] empty = new int[0];
    private static final int SAMPLE_LIMIT = 7292;

    private final Planet planet;
    private final int[] faces;
    private float[] points;
    private int nextSample;

    static UpstreamCampaignSurface open(Planet planet, Fi file) throws IOException {
        if (!file.exists()) return null;
        try (var input = new DataInputStream(file.read(4096))) {
            int magic = input.readInt(), version = input.readInt();
            int sectors = input.readInt(), samples = input.readInt();
            if (magic != 0x4D335053 || version != 1 || sectors != planet.sectors.size
                || samples != SAMPLE_LIMIT) throw new IOException("Unexpected stock Serpulo surface samples");

            boolean changed = false;
            for (var sector : planet.sectors) {
                int flags = (sector.hasEnemyBase() ? 1 : 0) | (sector.isCaptured() ? 2 : 0)
                    | (sector.hasBase() ? 4 : 0);
                int baseline = input.readInt();
                int coverage = input.readInt();
                changed |= flags != baseline || Float.floatToIntBits(sector.info.lightCoverage) != coverage;
            }
            return changed ? new UpstreamCampaignSurface(planet, input, samples) : null;
        }
    }

    private UpstreamCampaignSurface(Planet planet, DataInputStream input, int samples) throws IOException {
        this.planet = planet;
        faces = new int[samples * 3];
        points = new float[samples * 3];
        for (int offset = 0; offset < points.length; offset += 3) {
            int size = input.readUnsignedByte();
            if (size != 5 && size != 6) throw new IOException("Invalid stock surface polygon size");
            faces[offset] = size;
            points[offset] = input.readFloat();
            points[offset + 1] = input.readFloat();
            points[offset + 2] = input.readFloat();
        }
        if (input.read() != -1) throw new IOException("Unexpected trailing stock surface sample data");
    }

    private final Vec3 point = new Vec3();
    private final Color tint = new Color();

    void update(int budget) {
        if (points == null) return;
        var generator = planet.generator;
        int priorSeed = generator.seed;
        generator.seed = generator.baseSeed;
        try {
            int end = Math.min(nextSample + budget * 3, points.length);
            for (; nextSample < end; nextSample += 3) {
                point.set(points[nextSample], points[nextSample + 1], points[nextSample + 2]);
                generator.getColor(point, tint.set(1f, 1f, 1f, 1f));
                faces[nextSample + 1] = Float.floatToRawIntBits(tint.toFloatBits());
                generator.getEmissiveColor(point, tint.set(0f, 0f, 0f, 0f));
                faces[nextSample + 2] = Float.floatToRawIntBits(tint.toFloatBits());
            }
            if (nextSample == points.length) points = null;
        } finally {
            generator.seed = priorSeed;
        }
    }

    static int[] packedFaces(UpstreamCampaignSurface surface) {
        return surface == null || surface.points != null ? empty : surface.faces;
    }
}
