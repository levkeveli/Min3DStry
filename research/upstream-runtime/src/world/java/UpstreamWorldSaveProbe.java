import java.io.IOException;

public final class UpstreamWorldSaveProbe {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            UpstreamWorldLoadProbe.main(new String[] {
                "/3ds/min3dstry/glacier.msav",
                "/3ds/min3dstry/glacier-v13.msav"
            });
        } else if (args.length == 2) {
            UpstreamWorldLoadProbe.main(args);
        } else {
            throw new IllegalArgumentException("Expected paths to the Glacier map and v13 save");
        }
    }
}
