import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.type.UnitType;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.units.UnitFactory;
import static mindustry.Vars.player;

/** Touch equivalent of the upstream sorter item and factory plan tables. */
final class UpstreamBuildingConfig {
    private record Choice(UnlockableContent content, Object value) {}

    private final Seq<Choice> choices = new Seq<>();
    private Building target;
    private int page;
    private byte[] labels;
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];

    void open(Building build) {
        if (!(build instanceof Sorter.SorterBuild || build instanceof UnitFactory.UnitFactoryBuild)
                || !build.isValid() || !build.block.configurable || !Units.canInteract(player, build)
                || !build.configTapped()) return;
        choices.clear();
        if (build instanceof Sorter.SorterBuild) {
            for (var item : Vars.content.items()) {
                if (UpstreamItemChoices.accepts(item)) choices.add(new Choice(item, item));
            }
        } else if (build.block instanceof UnitFactory factory) {
            for (int index = 0; index < factory.plans.size; index++) {
                var unit = factory.plans.get(index).unit;
                if (unit.unlockedNow() && !unit.isBanned()) choices.add(new Choice(unit, index));
            }
            if (choices.isEmpty()) return;
        }
        target = build;
        page = Math.max(0, selectedIndex()) / UpstreamBuildMenu.ROWS;
        refreshLabels();
        System.out.println("UPSTREAM_CONFIG_OPEN x=" + build.tileX() + " y=" + build.tileY());
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
            if (choice.content() instanceof UnitType unit) {
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
        if (isOpen() && (!target.isValid() || !Units.canInteract(player, target)
                || target.shouldHideConfigure(player))) close();
    }

    private void close() {
        target.onConfigureClosed();
        target = null;
    }

    private void refreshLabels() {
        int pages = Math.max(1, (choices.size + UpstreamBuildMenu.ROWS - 1) / UpstreamBuildMenu.ROWS);
        var result = new StringBuilder(target.block.localizedName).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < choices.size) {
                var content = choices.get(index).content();
                result.append(content.localizedName);
                iconIds[row] = content.id;
            } else {
                iconIds[row] = -1;
            }
            result.append('\0');
        }
        labels = result.toString().getBytes(StandardCharsets.UTF_8);
    }

    boolean isOpen() { return target != null; }
    byte[] labels() { return labels; }
    int[] iconIds() { return iconIds; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < choices.size; }
    boolean unitPlans() { return target instanceof UnitFactory.UnitFactoryBuild; }
    int selectedRow() { return selectedIndex() - page * UpstreamBuildMenu.ROWS; }

    private int selectedIndex() {
        Object value = target.config();
        for (int index = 0; index < choices.size; index++) {
            if (Objects.equals(choices.get(index).value(), value)) return index;
        }
        return -1;
    }
}
