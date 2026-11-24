import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.ObjectFloatMap;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.type.ItemStack;
import mindustry.type.LiquidStack;
import mindustry.world.meta.StatUnit;
import mindustry.world.meta.StatValues;
import mindustry.world.blocks.defense.turrets.ItemTurret;

public final class UpstreamCoreDatabaseReference {
    public static void main(String[] args) {
        Vars.headless = true;
        Core.settings = new Settings();
        Core.bundle = I18NBundle.createBundle(new Fi(args[0]), Locale.ROOT);
        Vars.state = new GameState();
        Vars.world = new mindustry.core.World();
        Vars.indexer = new mindustry.ai.BlockIndexer();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        var database = new UpstreamCoreDatabase();
        var tabs = database.tabs();
        if (tabs.get(0) != Planets.sun || !tabs.contains(Planets.serpulo) || !tabs.contains(Planets.erekir)) {
            throw new AssertionError(tabs);
        }
        var all = database.groups(Planets.sun, "").stream().flatMap(group -> group.entries().stream()).toList();
        long expected = 0;
        for (var type : Vars.content.getContentMap()) {
            for (var content : type) {
                if (content instanceof mindustry.ctype.UnlockableContent u && !u.isHidden() && !u.hideDatabase) expected++;
            }
        }
        if (all.size() != expected || all.stream().noneMatch(entry -> !entry.unlocked())) throw new AssertionError("Stock catalog or locks");
        var search = database.groups(Planets.serpulo, "COPPER").stream().flatMap(group -> group.entries().stream()).toList();
        if (search.isEmpty() || search.stream().anyMatch(entry -> !entry.content().localizedName
                .toLowerCase(Locale.ROOT).contains("copper") || (!entry.content().allDatabaseTabs
                && !entry.content().databaseTabs.contains(Planets.serpulo)))) throw new AssertionError("Stock search");
        Vars.state.rules.planet = Planets.erekir;
        Vars.state.set(GameState.State.playing);
        if (database.initialTab(tabs) != Planets.erekir || database.groups(Planets.sun, "").stream()
                .flatMap(group -> group.entries().stream()).anyMatch(entry -> !entry.unlocked())) throw new AssertionError("Stock game mode");
        verifyStatLabels();
        int displayableStats = 0;
        for (var entry : all) {
            for (var category : entry.content().computeStats().toMap().values()) {
                for (var stat : category.keys()) {
                    for (var value : category.get(stat)) {
                        String label = value.handheldText();
                        if (label == null && stat == mindustry.world.meta.Stat.output) {
                            label = UpstreamCoreDatabaseProduction.productionOutput(entry.content());
                        }
                        if (label != null && !label.isEmpty()) displayableStats++;
                    }
                }
            }
        }
        if (displayableStats == 0) throw new AssertionError("No stock detail stats");
        System.out.printf("Stock database catalog=%d tabs=%d Serpulo copper entries=%d displayable stats=%d%n",
            expected, tabs.size(), search.size(), displayableStats);
    }

    private static void verifyStatLabels() {
        String distance = StatValues.number(4.5f, StatUnit.blocks).handheldText();
        String itemRate = StatValues.items(30f, new ItemStack(Items.copper, 7)).handheldText();
        String liquidRate = StatValues.liquids(30f, true, new LiquidStack(Liquids.water, 1.25f)).handheldText();
        String filteredItem = StatValues.items(30f, item -> item == Items.copper).handheldText();
        String filteredLiquid = StatValues.liquids(liquid -> liquid == Liquids.water, 2.5f, true).handheldText();
        String area = StatValues.squared(4.5f, StatUnit.blocks).handheldText();
        if (!"4.5 blocks".equals(distance) || !"Copper 7 (14/sec)".equals(itemRate) || !"Water 2.5/sec".equals(liquidRate)) {
            throw new AssertionError(distance + " | " + itemRate + " | " + liquidRate);
        }
        if (!"Copper 1 (2/sec)".equals(filteredItem) || !"Water 2.5/sec".equals(filteredLiquid) || !"4.5x4.5 blocks".equals(area)) {
            throw new AssertionError(filteredItem + " | " + filteredLiquid + " | " + area);
        }
        var materials = arc.struct.Seq.with(Items.copper, Items.lead);
        String material = StatValues.content(Items.lead).handheldText();
        String selected = StatValues.content(materials, item -> item == Items.copper, "@none").handheldText();
        String empty = StatValues.content(materials, item -> false, "@none").handheldText();
        if (!"Lead".equals(material) || !"Copper".equals(selected) || !Core.bundle.get("none").equals(empty)) {
            throw new AssertionError(material + " | " + selected + " | " + empty);
        }
        var drillMultipliers = new ObjectFloatMap<mindustry.type.Item>();
        drillMultipliers.put(Items.copper, 2f);
        String drills = StatValues.drillables(120f, 30f, 4f, drillMultipliers,
            block -> block == Blocks.oreCopper).handheldText();
        if (!"Copper / Copper 3.2/sec".equals(drills)) throw new AssertionError(drills);
        var duo = (ItemTurret) Blocks.duo;
        String ammo = StatValues.ammo(duo.ammoTypes).handheldText();
        String copperDamage = Core.bundle.format("bullet.damage", duo.ammoTypes.get(Items.copper).damage);
        if (!ammo.contains(Items.copper.localizedName + "\n" + copperDamage)) {
            throw new AssertionError(ammo);
        }
        var repair = new mindustry.entities.abilities.RepairFieldAbility(20f, 120f, 80f);
        String ability = StatValues.abilities(arc.struct.Seq.with(repair)).handheldText();
        if (!ability.contains(repair.abilityStat("repairspeed", "10"))) throw new AssertionError(ability);
        var duration = new ObjectFloatMap<mindustry.type.Item>();
        duration.put(Items.copper, 2f);
        String itemFuel = StatValues.itemEffMultiplier(item -> 1.25f, 30f, item -> item == Items.copper, duration).handheldText();
        String liquidFuel = StatValues.liquidEffMultiplier(liquid -> 1.25f, 2.5f, liquid -> liquid == Liquids.water).handheldText();
        String efficiency = Core.bundle.format("stat.efficiency", "125");
        if (!itemFuel.contains("Copper 1/sec " + efficiency) || !liquidFuel.contains("Water 2.5/sec " + efficiency)) {
            throw new AssertionError(itemFuel + " | " + liquidFuel);
        }
        var factory = UpstreamCoreDatabaseProduction.productionOutput(Blocks.groundFactory);
        var upgrades = UpstreamCoreDatabaseProduction.productionOutput(Blocks.additiveReconstructor);
        if (!factory.contains(UnitTypes.dagger.localizedName) || !upgrades.contains(UnitTypes.mace.localizedName)) {
            throw new AssertionError(factory + " | " + upgrades);
        }
    }
}
