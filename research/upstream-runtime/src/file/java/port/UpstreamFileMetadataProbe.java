package port;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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

        File renameDir = new File(glacier.getParentFile(), "tmp");
        if (!renameDir.isDirectory() && !renameDir.mkdirs()) {
            throw new IllegalStateException("Could not create rename directory");
        }
        File source = File.createTempFile("m3ds-rename-", ".bin", renameDir);
        File target = new File(renameDir, source.getName() + ".moved");
        byte[] payload = {0x4d, 0x33, 0x44, 0x53};
        try {
            try (var output = new FileOutputStream(source)) {
                output.write(payload);
            }
            if (!source.renameTo(target)) {
                throw new IllegalStateException("TeaVM file rename failed");
            }
            if (source.exists() || !target.isFile() || target.length() != payload.length) {
                throw new IllegalStateException("Renamed file metadata differs");
            }
            try (var input = new FileInputStream(target)) {
                for (byte expected : payload) {
                    if (input.read() != (expected & 0xff)) {
                        throw new IllegalStateException("Renamed file content differs");
                    }
                }
                if (input.read() != -1) {
                    throw new IllegalStateException("Renamed file has trailing bytes");
                }
            }
            System.out.println("UPSTREAM_FILE_RENAME bytes=" + payload.length + " rename=ok");
        } finally {
            target.delete();
            source.delete();
        }
        System.out.println("UPSTREAM_FILE_METADATA size=8550 header=789cbd3a last=108 seek=ok paths=ok");
    }
}
