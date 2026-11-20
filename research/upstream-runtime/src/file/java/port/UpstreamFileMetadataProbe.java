package port;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Exercises the native Java filesystem against the pinned Glacier fixture. */
public final class UpstreamFileMetadataProbe {
    private static final String SD_HOME = "/3ds/min3dstry";
    private static final int SIZE = 8550;
    private static final int HEADER = 0x789cbd3a;
    private static final int LAST_BYTE = 108;

    public static void main(String[] args) throws IOException {
        String home = System.getProperty("user.home");
        String temporary = System.getProperty("java.io.tmpdir");
        if (home == null || home.isEmpty() || temporary == null || temporary.isEmpty()
                || (args.length == 0 && (!SD_HOME.equals(home) || !(SD_HOME + "/tmp").equals(temporary)))) {
            throw new IllegalStateException("Unexpected application or temporary path");
        }
        File glacier = args.length == 0 ? new File(SD_HOME + "/glacier.msav") : new File(args[0]);
        if (!glacier.isFile() || glacier.length() != SIZE) {
            throw new IllegalStateException("Glacier file metadata differs");
        }
        try (var input = new RandomAccessFile(glacier, "r")) {
            if (input.length() != SIZE || input.getFilePointer() != 0 || input.readInt() != HEADER
                    || input.getFilePointer() != 4) {
                throw new IllegalStateException("Initial Glacier read differs");
            }
            input.seek(SIZE - 1);
            if (input.getFilePointer() != SIZE - 1 || input.read() != LAST_BYTE
                    || input.getFilePointer() != SIZE || input.read() != -1) {
                throw new IllegalStateException("Glacier seek or EOF differs");
            }
            input.seek(0);
            if (input.getFilePointer() != 0 || input.readInt() != HEADER) {
                throw new IllegalStateException("Glacier rewind differs");
            }
        }
        System.out.println("UPSTREAM_FILE_METADATA size=8550 header=789cbd3a last=108 seek=ok paths=ok");
    }
}
