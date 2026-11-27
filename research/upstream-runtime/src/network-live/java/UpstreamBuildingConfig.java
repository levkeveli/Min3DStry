import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import mindustry.Vars;
import mindustry.ai.UnitCommand;
import mindustry.ctype.MappableContent;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.payloads.PayloadMassDriver;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import mindustry.world.blocks.units.UnitFactory;
import static mindustry.Vars.player;

/** Touch access to upstream configuration tables and building links. */
final class UpstreamBuildingConfig {
    private record Choice(MappableContent content, Object value) {
        String label() {
            return content instanceof UnitCommand command ? command.localized() : ((UnlockableContent)content).localizedName;
        }
    }

    private final Seq<Choice> choices = new Seq<>();
    private Building target;
    private boolean linking;
    private int page;
    private byte[] labels;
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];

    void open(Building build) {
        if (build == null || !build.isValid() || !build.block.configurable || !Units.canInteract(player, build)
                || !build.shouldShowConfigure(player)) return;
        if (build instanceof PowerNode.PowerNodeBuild || build instanceof ItemBridge.ItemBridgeBuild
                || build instanceof MassDriver.MassDriverBuild || build instanceof PayloadMassDriver.PayloadDriverBuild) {
            if (!build.configTapped()) return;
            target = build;
            linking = true;
            System.out.println("UPSTREAM_LINK_CONFIG x=" + build.tileX() + " y=" + build.tileY()
                + " block=" + build.block.name);
            return;
        }
        boolean itemSelector = build.block.configurations.containsKey(Item.class);
        boolean supported = itemSelector || build instanceof UnitFactory.UnitFactoryBuild
            || build instanceof ReconstructorBuild;
        if (!supported || !build.configTapped()) return;
        choices.clear();
        if (itemSelector) {
            for (var item : Vars.content.items()) {
                if (UpstreamItemChoices.accepts(item)) choices.add(new Choice(item, item));
            }
        } else if (build.block instanceof UnitFactory factory) {
            for (int index = 0; index < factory.plans.size; index++) {
                var unit = factory.plans.get(index).unit;
                if (unit.unlockedNow() && !unit.isBanned()) choices.add(new Choice(unit, index));
            }
        } else if (build instanceof ReconstructorBuild reconstructor) {
            var unit = reconstructor.unit();
            var commands = unit == null ? Vars.content.unitCommands() : unit.commands;
            for (var command : commands) choices.add(new Choice(command, command));
        }
        if (choices.isEmpty()) return;
        target = build;
        page = Math.max(0, selectedIndex()) / UpstreamBuildMenu.ROWS;
        refreshLabels();
        System.out.println("UPSTREAM_CONFIG_OPEN x=" + build.tileX() + " y=" + build.tileY());
    }

    void tapLink(Building other) {
        if (other == null) {
            close();
        } else if (Units.canInteract(player, other)) {
            if (target.onConfigureBuildTapped(other)) {
                close();
                open(other);
            } else if (other == target && target instanceof PowerNode.PowerNodeBuild) {
                close();
            }
        }
    }

    void tap(int x, int y) {
        if (y >= UpstreamBuildMenu.FOOTER_TOP) {
            if (x < 100 && previousPage()) {
                page--;
                refreshLabels();
            } else if (x >= 220 && nextPage()) {
                page++;
                refreshLabels();
            } else if (x >= 100 && x < 220) {
                close();
            }
        } else if (y >= UpstreamBuildMenu.ROW_TOP && x >= 8 && x < 312) {
            int row = (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (row >= UpstreamBuildMenu.ROWS || index >= choices.size) return;
            var choice = choices.get(index);
            boolean clear = Objects.equals(choice.value(), target.config());
            if (choice.content() instanceof UnitCommand command) {
                target.configure(command);
                System.out.println("UPSTREAM_RECONSTRUCTOR_COMMAND x=" + target.tileX() + " y=" + target.tileY()
                    + " command=" + command.name);
            } else if (choice.content() instanceof UnitType unit) {
                if (!unit.unlockedNow() || unit.isBanned()) {
                    close();
                    return;
                }
                int plan = clear ? -1 : (Integer)choice.value();
                target.configure(plan);
                System.out.println("UPSTREAM_FACTORY_PLAN x=" + target.tileX() + " y=" + target.tileY()
                    + " plan=" + plan + " unit=" + (clear ? "none" : unit.name));
            } else {
                target.configure(clear ? null : choice.value());
                System.out.println("UPSTREAM_CONFIG_LOCAL x=" + target.tileX() + " y=" + target.tileY()
                    + " item=" + (clear ? "none" : choice.content().name));
            }
            close();
        }
    }

    void validate() {
        if (target != null && (!target.isValid() || !Units.canInteract(player, target)
                || target.shouldHideConfigure(player))) close();
    }

    void close() {
        target.onConfigureClosed();
        target = null;
        linking = false;
    }

    private void refreshLabels() {
        int pages = Math.max(1, (choices.size + UpstreamBuildMenu.ROWS - 1) / UpstreamBuildMenu.ROWS);
        var result = new StringBuilder(target.block.localizedName).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < choices.size) {
                var choice = choices.get(index);
                result.append(choice.label());
                iconIds[row] = choice.content().id;
            } else {
                iconIds[row] = -1;
            }
            result.append('\0');
        }
        labels = result.toString().getBytes(StandardCharsets.UTF_8);
    }

    boolean isOpen() { return target != null && !linking; }
    boolean isLinking() { return linking; }
    Building linkSource() { return linking ? target : null; }
    byte[] labels() { return labels; }
    int[] iconIds() { return iconIds; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < choices.size; }
    boolean unitPlans() { return target instanceof UnitFactory.UnitFactoryBuild; }
    boolean reconstructorCommands() { return target instanceof ReconstructorBuild; }
    int selectedRow() { return selectedIndex() - page * UpstreamBuildMenu.ROWS; }

    private int selectedIndex() {
        Object value = target.config();
        for (int index = 0; index < choices.size; index++) {
            if (Objects.equals(choices.get(index).value(), value)) return index;
        }
        return -1;
    }
}
