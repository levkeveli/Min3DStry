import java.io.*;
import java.util.HexFormat;
import arc.util.io.*;
import mindustry.world.modules.PowerModule;

public class UpstreamPowerLinkReference {
    static byte[] encode(PowerModule power) throws Exception {
        var bytes = new ByteArrayOutputStream();
        power.write(Writes.get(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        var power = new PowerModule();
        power.status = 0.375f;
        for(int i = 0; i < 45; i++){
            if(i > 0) power.links.add((i << 16) | (3 * i));
            if(i > 4 && i % 5 == 0) power.links.removeIndex(1);
            byte[] encoded = encode(power);
            var restored = new PowerModule();
            restored.links.add(-1);
            restored.read(Reads.get(new DataInputStream(new ByteArrayInputStream(encoded))));
            if(!java.util.Arrays.equals(encoded, encode(restored))) throw new AssertionError("Bad power link round trip");
            System.out.println(HexFormat.of().formatHex(encoded));
        }
    }
}