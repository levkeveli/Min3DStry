import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.Liquid;
import mindustry.world.modules.LiquidModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Random;

/** Runs against both the stock server JAR and the staged handheld LiquidModule. */
public final class UpstreamLiquidInventoryReference {
    public static void main(String[] args) throws IOException {
        Vars.content = new ContentLoader();
        var liquids = new Liquid[]{new Liquid("water"), new Liquid("slag"), new Liquid("oil")};
        var module = new LiquidModule();
        var random = new Random(538);
        float[] amounts = {0f, -0f, 1f, -2f, 1.25f, Float.NaN, Float.POSITIVE_INFINITY};

        for (int step = 0; step < 2000; step++) {
            var liquid = liquids[random.nextInt(liquids.length)];
            float amount = amounts[random.nextInt(amounts.length)];
            switch (random.nextInt(9)) {
                case 0 -> module.reset(liquid, amount);
                case 1 -> module.set(liquid, amount);
                case 2 -> module.add(liquid, amount);
                case 3 -> module.remove(liquid, amount);
                case 4 -> module.clear();
                case 5 -> read(module, save(module), false);
                case 6 -> {
                    var bytes = new ByteArrayOutputStream();
                    var out = new DataOutputStream(bytes);
                    out.writeByte(2);
                    // The legacy reader skips this unknown ID and still reads the next record.
                    out.writeByte(255);
                    out.writeFloat(4f);
                    out.writeByte(liquid.id);
                    out.writeFloat(amount);
                    read(module, bytes.toByteArray(), true);
                    if (Float.floatToRawIntBits(module.get(liquid)) != Float.floatToRawIntBits(amount)) {
                        throw new AssertionError("Legacy liquid record did not round-trip");
                    }
                }
                case 7 -> {
                    module.checkArrayCapacity(4);
                    module.checkArrayCapacity(3);
                }
                default -> module.handleFlow(liquid, amount);
            }
            System.out.print(module.current().id);
            // HotSpot can choose different NaN payloads for equivalent arithmetic.
            for (var value : liquids) System.out.print(" " + Float.floatToIntBits(module.get(value)));
            module.each((type, value) -> System.out.print(" " + type.id + "=" + value));
            System.out.println(" " + module.sum((type, value) -> type.id * value)
                + " " + HexFormat.of().formatHex(save(module)));
        }

        module.clear();
        int ready = 0;
        for (int step = 1; step <= 100; step++) {
            Time.time = step * 10;
            if (step == 60) {
                module.checkArrayCapacity(1);
                try {
                    module.get(liquids[2]);
                    throw new AssertionError("Shrinking must remove the old oil slot");
                } catch (ArrayIndexOutOfBoundsException expected) {
                    // updateFlow restores stock catalog capacity at the next sample.
                }
            }
            module.updateFlow();
            module.add(liquids[0], step / 4f);
            if (step % 5 == 0) module.add(liquids[2], 9f);
            if (step % 17 == 0) module.clear();
            float rate = module.getFlowRate(liquids[0]);
            if (rate > 0) ready++;
            System.out.println(rate + " " + module.hasFlowLiquid(liquids[2]) + " "
                + module.get(liquids[0]) + " " + module.get(liquids[2]));
        }
        module.stopFlow();
        if (ready == 0 || module.getFlowRate(liquids[0]) != -1f) {
            throw new AssertionError("Stock flow sampling did not start and stop");
        }
    }

    private static byte[] save(LiquidModule module) {
        var bytes = new ByteArrayOutputStream();
        module.write(new Writes(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    private static void read(LiquidModule module, byte[] bytes, boolean legacy) {
        module.read(new Reads(new DataInputStream(new ByteArrayInputStream(bytes))), legacy);
    }
}
