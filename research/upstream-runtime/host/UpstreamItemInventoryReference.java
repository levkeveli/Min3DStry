import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.Item;
import mindustry.world.modules.ItemModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Random;

/** Runs against the stock and staged ItemModule in separate JVMs. */
public final class UpstreamItemInventoryReference {
    public static void main(String[] args) throws IOException {
        Vars.content = new ContentLoader();
        var items = new Item[]{new Item("copper"), new Item("lead"), new Item("graphite")};
        // Pinned Content and MappableContent register each constructor call with Vars.content.
        if (Vars.content.items().size != items.length) throw new AssertionError("Stock item registration changed");
        for (int i = 0; i < items.length; i++) {
            if (items[i].id != i || Vars.content.item(i) != items[i]) {
                throw new AssertionError("Stock item IDs changed");
            }
        }
        var module = new ItemModule();
        var random = new Random(538);
        int[] amounts = {0, 1, -2, 5, 512, -200, 22};
        for (int step = 0; step < 2000; step++) {
            var item = items[random.nextInt(items.length)];
            int amount = amounts[random.nextInt(amounts.length)];
            switch (random.nextInt(9)) {
                case 0 -> System.out.print("take=" + module.take() + " ");
                case 1 -> module.set(item, amount);
                case 2 -> module.add(item, amount);
                case 3 -> module.remove(item, amount);
                case 4 -> module.clear();
                case 5 -> read(module, save(module), false);
                case 6 -> {
                    var bytes = new ByteArrayOutputStream();
                    var out = new DataOutputStream(bytes);
                    out.writeByte(2);
                    out.writeByte(255);
                    out.writeInt(4);
                    out.writeByte(item.id);
                    out.writeInt(amount);
                    read(module, bytes.toByteArray(), true);
                }
                case 7 -> {
                    module.checkArrayCapacity(4);
                    module.checkArrayCapacity(3);
                }
                default -> module.add(module.copy());
            }
            System.out.print(module.total() + " " + module.copy());
            for (var value : items) System.out.print(" " + module.get(value));
            module.each((type, value) -> System.out.print(" " + type.id + "=" + value));
            System.out.println(" " + module.sum((type, value) -> type.id * value)
                + " " + HexFormat.of().formatHex(save(module)));
        }

        module.clear();
        int ready = 0;
        for (int step = 1; step <= 100; step++) {
            Time.time = step * 10;
            if (step == 60) module.checkArrayCapacity(1);
            module.updateFlow();
            module.add(items[0], step % 6);
            if (step % 5 == 0) module.add(items[1], 2);
            if (step % 17 == 0) module.clear();
            float rate = module.getFlowRate(items[0]);
            if (rate > 0) ready++;
            System.out.println(rate + " " + module.hasFlowItem(items[1])
                + " " + module.get(items[0]) + " " + module.get(items[1]));
        }
        module.stopFlow();
        if (ready == 0 || module.getFlowRate(items[0]) != -1f) {
            throw new AssertionError("Stock item flow sampling did not start and stop");
        }

        var from = new ItemModule();
        var into = new ItemModule();
        from.add(items[0], 7);
        from.add(items[1], 11);
        into.add(items[1], 2);
        into.add(items[2], 3);
        into.set(from);
        if (into.total() != 18 || into.get(items[0]) != 7 || into.get(items[1]) != 11
                || into.get(items[2]) != 0 || into.take() != items[0] || into.total() != 17) {
            throw new AssertionError("Stock multi-entry copy changed inventory, total or take order");
        }
        var subclass = new ArrayModule();
        subclass.verify(items[0], items[1]);
        System.out.println("UPSTREAM_STOCK_ITEM_SUBCLASS array=preserved");
    }

    private static byte[] save(ItemModule module) {
        var bytes = new ByteArrayOutputStream();
        module.write(new Writes(new DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    private static void read(ItemModule module, byte[] bytes, boolean legacy) {
        module.read(new Reads(new DataInputStream(new ByteArrayInputStream(bytes))), legacy);
    }

    private static final class ArrayModule extends ItemModule {
        @Override
        public int get(int id) {
            throw new AssertionError("Stock internal item operations bypass virtual get(int)");
        }

        void verify(Item copper, Item lead) {
            if (items.length != 3) throw new AssertionError("The protected array must exist at construction");
            var original = items;
            items[copper.id] = 7;
            total = 7;
            add(lead, 4);
            if (items[lead.id] != 4 || take() != copper || items[copper.id] != 6) {
                throw new AssertionError("Stock methods must operate on the protected array");
            }
            clear();
            if (items != original || items[copper.id] != 0 || items[lead.id] != 0 || total != 0) {
                throw new AssertionError("Stock clear must retain and zero the subclass array");
            }
            items = new int[]{3, 0};
            total = 3;
            if (length() != 2 || copyIntoEqualCapacity().get(copper) != 3) {
                throw new AssertionError("Stock subclass array replacement changed");
            }
            var other = new ArrayModule();
            other.items = new int[2];
            var storage = other.items;
            other.set(this);
            other.add(this);
            if (other.items != storage || other.items[copper.id] != 6 || other.total() != 6) {
                throw new AssertionError("Stock subclass copy and add changed the replacement array");
            }
            var saved = save(other);
            other.clear();
            UpstreamItemInventoryReference.read(other, saved, false);
            if (other.items != storage || other.items[copper.id] != 6 || other.total() != 6) {
                throw new AssertionError("Stock subclass save and read changed the replacement array");
            }
            other.checkArrayCapacity(4);
            if (other.length() != 4 || other.items[copper.id] != 6) {
                throw new AssertionError("Stock subclass resize lost a populated slot");
            }
        }

        private ItemModule copyIntoEqualCapacity() {
            var copy = new ItemModule();
            copy.checkArrayCapacity(items.length);
            copy.set(this);
            return copy;
        }
    }
}
