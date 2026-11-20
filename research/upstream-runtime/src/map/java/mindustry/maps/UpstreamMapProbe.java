package mindustry.maps;

import arc.files.Fi;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import mindustry.io.MapIO;

public final class UpstreamMapProbe {
    private static final class Fixture extends Fi {
        @Override
        public BufferedInputStream read(int size) {
            byte[] data = Base64.getDecoder().decode(GlacierFixture.BASE64);
            return new BufferedInputStream(new ByteArrayInputStream(data), size);
        }
    }

    public static void main(String[] args) throws Exception {
        check(new Fixture());
    }

    static void check(Fi fixture) throws Exception {
        Map map = MapIO.createMap(fixture, false);
        String name = map.tags.get("name");
        if (!"Glacier".equals(name) || map.width != 150 || map.height != 250 || map.version != 7) {
            throw new AssertionError("Unexpected upstream Glacier metadata");
        }
        System.out.println("UPSTREAM_MAP name=" + name + " width=" + map.width
            + " height=" + map.height + " version=" + map.version);
    }
}
