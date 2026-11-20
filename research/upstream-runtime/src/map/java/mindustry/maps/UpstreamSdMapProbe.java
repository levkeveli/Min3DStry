package mindustry.maps;

import arc.files.Fi;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.Include;

public final class UpstreamSdMapProbe {
    @Include(value = "m3ds_map_file.h", isSystem = true)
    private static final class NativeFile {
        @Import(name = "m3ds_map_open")
        static native Address open();

        @Import(name = "m3ds_map_read_byte")
        static native int readByte(Address file);

        @Import(name = "m3ds_map_read")
        static native int read(Address file, Address destination, int length);

        @Import(name = "m3ds_map_close")
        static native void close(Address file);
    }

    private static final class SdStream extends InputStream {
        private Address file = NativeFile.open();

        SdStream() throws IOException {
            if (file == null) {
                throw new IOException("Cannot open sdmc:/3ds/min3dstry/glacier.msav");
            }
        }

        @Override
        public int read() throws IOException {
            requireOpen();
            return checkRead(NativeFile.readByte(file));
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            requireOpen();
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            return checkRead(NativeFile.read(file, Address.ofData(bytes).add(offset), length));
        }

        private void requireOpen() throws IOException {
            if (file == null) {
                throw new IOException("Map stream is closed");
            }
        }

        private static int checkRead(int result) throws IOException {
            if (result < -1) {
                throw new IOException("Unable to read SD map");
            }
            return result;
        }

        @Override
        public void close() {
            if (file != null) {
                NativeFile.close(file);
                file = null;
            }
        }
    }

    private static final class SdFixture extends Fi {
        @Override
        public BufferedInputStream read(int size) {
            try {
                return new BufferedInputStream(new SdStream(), size);
            } catch (IOException error) {
                throw new IllegalStateException(error.getMessage(), error);
            }
        }
    }

    public static void main(String[] args) {
        try {
            UpstreamMapProbe.check(new SdFixture());
        } catch (Exception error) {
            System.out.println("UPSTREAM_MAP_ERROR " + error.getMessage());
        }
    }
}
