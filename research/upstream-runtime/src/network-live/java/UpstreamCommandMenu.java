import arc.Core;
import arc.struct.IntSeq;
import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ctype.MappableContent;
import mindustry.gen.Unit;
import mindustry.input.InputHandler;

/** Touch rows for the command and stance choices in upstream PlacementFragment. */
final class UpstreamCommandMenu {
    private final Seq<MappableContent> rows = new Seq<>();
    private final Seq<UnitStance> eligibleStances = new Seq<>();
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];
    private boolean open, stances;
    private int page;
    private byte[] labels;

    void open(Seq<Unit> units, boolean showStances) {
        rows.clear();
        stances = showStances;
        if (showStances && !units.isEmpty()) rows.add(UnitStance.stop);
        if (showStances) {
            for (Unit unit : units) {
                eligibleStances.clear();
                unit.type().getUnitStances(unit, eligibleStances);
                for (var stance : eligibleStances) {
                    if (!rows.contains(stance, true)) rows.add(stance);
                }
            }
        } else {
            for (var type : Vars.content.units()) {
                if (!units.contains(unit -> unit.type() == type)) continue;
                for (var command : type.commands) {
                    if (!rows.contains(command, true)) rows.add(command);
                }
            }
        }
        if (rows.isEmpty()) return;
        page = 0;
        refresh();
        open = true;
    }

    void tap(Seq<Unit> units, int x, int y) {
        if (y >= UpstreamBuildMenu.FOOTER_TOP) {
            if (x < 100 && previous()) {
                page--;
                refresh();
            } else if (x >= 220 && next()) {
                page++;
                refresh();
            } else if (x >= 100 && x < 220) {
                close();
            }
        } else if (y >= UpstreamBuildMenu.ROW_TOP && x >= 8 && x < 312) {
            int row = (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (row >= UpstreamBuildMenu.ROWS || index >= rows.size) return;
            choose(units, rows.get(index));
            close();
        }
    }

    private void choose(Seq<Unit> units, MappableContent entry) {
        if (Vars.player.dead() || Vars.state.isPaused()) return;
        var ids = new IntSeq();
        if (entry instanceof UnitCommand command) {
            for (Unit unit : units) {
                if (unit.type().allowCommand(unit, command)) ids.add(unit.id);
            }
            if (ids.isEmpty()) return;
            InputHandler.setUnitCommandChunked(ids, command);
            System.out.println("UPSTREAM_UNIT_COMMAND command=" + command.name + " units=" + ids.size);
        } else if (entry instanceof UnitStance stance) {
            for (Unit unit : units) {
                if (unit.type().allowStance(unit, stance)) ids.add(unit.id);
            }
            if (ids.isEmpty()) return;
            boolean enable = !active(units, stance);
            InputHandler.setUnitStanceChunked(ids, stance, enable);
            System.out.println("UPSTREAM_UNIT_STANCE stance=" + stance.name + " enabled=" + enable + " units=" + ids.size);
        }
    }

    private void refresh() {
        int pages = (rows.size + UpstreamBuildMenu.ROWS - 1) / UpstreamBuildMenu.ROWS;
        var text = new StringBuilder(stances ? "Stances" : Core.bundle.get("command")).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < rows.size) {
                var entry = rows.get(index);
                String label = entry instanceof UnitCommand command ? command.localized() : ((UnitStance)entry).localized();
                int details = label.indexOf('\n');
                text.append(label, 0, details < 0 ? label.length() : details);
                iconIds[row] = entry.id;
            } else {
                iconIds[row] = -1;
            }
            text.append('\0');
        }
        labels = text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean active(Seq<Unit> units, MappableContent entry) {
        if (entry instanceof UnitStance stance) {
            if (stance == UnitStance.stop) return false;
            // PlacementFragment copies each controller's stance bits over the previous unit's bits.
            for (int i = units.size - 1; i >= 0; i--) {
                var unit = units.get(i);
                if (unit.isCommandable()) return unit.command().hasStance(stance);
            }
            return false;
        }
        for (Unit unit : units) {
            if (!unit.isCommandable()) continue;
            if (unit.command().currentCommand() == entry) return true;
        }
        return false;
    }

    int activeRows(Seq<Unit> units) {
        int mask = 0;
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < rows.size && active(units, rows.get(index))) mask |= 1 << row;
        }
        return mask;
    }

    void close() { open = false; }
    boolean isOpen() { return open; }
    boolean stances() { return stances; }
    byte[] labels() { return labels; }
    int[] icons() { return iconIds; }
    boolean previous() { return page > 0; }
    boolean next() { return (page + 1) * UpstreamBuildMenu.ROWS < rows.size; }
}
