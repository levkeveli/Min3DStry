package port;

import arc.Files.FileType;
import arc.files.Fi;
import java.io.File;
import java.io.IOException;
import java.util.zip.CRC32;

public final class UpstreamResourceProbe {
    private static final String SERPULO = "planets/serpulo.json";
    private static final String ABSENT = "planets/not-in-upstream.json";
    private static final int SIZE = 709;
    private static final long CHECKSUM = 3921318438L;

    private static final class AssetFi extends Fi {
        AssetFi(String path, FileType type) {
            super(path, type);
        }

        @Override
        public File file() {
            // These two file types do not require Arc's external storage service.
            return file;
        }
    }

    public static void main(String[] args) throws IOException {
        check(FileType.classpath);
        check(FileType.internal);
        System.out.println("UPSTREAM_RESOURCE size=" + SIZE + " crc32=" + CHECKSUM
            + " classpath=ok internal=ok missing=ok");
    }

    private static void check(FileType type) throws IOException {
        Fi source = new AssetFi(SERPULO, type);
        if (!source.exists() || new AssetFi(ABSENT, type).exists()) {
            throw new IllegalStateException("Unexpected " + type + " resource existence");
        }
        byte[] contents;
        try (var input = source.read()) {
            contents = input.readAllBytes();
        }
        CRC32 crc = new CRC32();
        crc.update(contents, 0, contents.length);
        if (contents.length != SIZE || crc.getValue() != CHECKSUM) {
            throw new IllegalStateException("Unexpected " + type + " resource contents");
        }
    }
}
