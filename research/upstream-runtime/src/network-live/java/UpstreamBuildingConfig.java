import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Strings;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Function;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.payloads.PayloadMassDriver;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.units.UnitFactory;
import static mindustry.Vars.player;

/** Touch access to upstream configuration tables and building links. */
final class UpstreamBuildingConfig {
    private record Choice(UnlockableContent content, Object value) {}

    private final Seq<Choice> choices = new Seq<>();
    private Building target;
    private boolean linking;
    private int page;
    private byte[] labels;
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];

    private Function<String, String> processorEditor;
    private Fi logicClipboard;
    private enum LogicView { program, statement, palette }
    private UpstreamLogicStatements instructionProgram;
    private LogicView instructionView;
    private int focusedStatement;
    private boolean instructionsChanged;

    void editProcessorsWith(Function<String, String> editor, Fi clipboard) {
        processorEditor = editor;
        logicClipboard = clipboard;
    }

    void open(Building build) {
        if (build == null || !build.isValid() || !build.block.configurable || !Units.canInteract(player, build)
                || !build.shouldShowConfigure(player)) return;
        if (build instanceof LogicBuild && processorEditor != null) {
            if (!build.configTapped()) return;
            target = build;
            linking = false;
            page = 0;
            choices.clear();
            Arrays.fill(iconIds, -1);
            refreshProcessorLabels(build.block.localizedName);
            System.out.println("UPSTREAM_PROCESSOR_CONFIG_OPEN x=" + build.tileX() + " y=" + build.tileY());
            return;
        }
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
        if ((!itemSelector && !(build instanceof UnitFactory.UnitFactoryBuild)) || !build.configTapped()) return;
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
        } else if (target instanceof LogicBuild processor) {
            if (other == processor) {
                close();
            } else if (UpstreamProcessorConfiguration.toggleLink(processor, other)) {
                System.out.println("UPSTREAM_PROCESSOR_LINK x=" + other.tileX() + " y=" + other.tileY());
            }
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
                if (instructionView == LogicView.program) {
                    instructionProgram = null;
                    instructionView = null;
                    refreshProcessorLabels(target.block.localizedName);
                } else if (instructionView != null) {
                    showInstructions(LogicView.program);
                } else {
                    close();
                }
            }
        } else if (y >= UpstreamBuildMenu.ROW_TOP && x >= 8 && x < 312) {
            int row = (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            if (row >= UpstreamBuildMenu.ROWS) return;
            if (target instanceof LogicBuild processor) {
                if (instructionView == null) tapProcessor(processor, row);
                else tapInstruction(processor, page * UpstreamBuildMenu.ROWS + row);
                return;
            }
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index >= choices.size) return;
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

    private void tapProcessor(LogicBuild processor, int row) {
        switch (row) {
            case 0 -> {
                if (processor.code.length() > UpstreamProcessorConfiguration.APPLET_CHARACTERS) {
                    refreshProcessorLabels(Core.bundle.get("asset.import.file"));
                    return;
                }
                String code = processorEditor.apply(processor.code);
                if (code != null) applyProgram(processor, code);
            }
            case 1 -> {
                try {
                    if (!logicClipboard.exists() || logicClipboard.isDirectory()
                            || logicClipboard.length() > UpstreamProcessorConfiguration.MAX_SOURCE_BYTES) {
                        refreshProcessorLabels(Core.bundle.get("editor.errorload"));
                        return;
                    }
                    applyProgram(processor, logicClipboard.readString("UTF-8"));
                } catch (RuntimeException error) {
                    refreshProcessorLabels(Core.bundle.get("editor.errorload"));
                }
            }
            case 2 -> {
                try {
                    logicClipboard.writeString(processor.code, false, "UTF-8");
                    refreshProcessorLabels(Core.bundle.get("editor.saved"));
                    System.out.println("UPSTREAM_PROCESSOR_EXPORT file=" + logicClipboard.name());
                } catch (RuntimeException error) {
                    refreshProcessorLabels(Core.bundle.get("editor.errorsave"));
                }
            }
            case 3 -> linking = true;
            case 4 -> {
                instructionProgram = new UpstreamLogicStatements(processor.code, processor.block.privileged,
                    Vars.state.rules.logicUnitControl);
                instructionsChanged = false;
                showInstructions(LogicView.program);
            }
            default -> { }
        }
    }

    private void showInstructions(LogicView view) {
        instructionView = view;
        page = view == LogicView.program ? Math.max(0, focusedStatement + 2) / UpstreamBuildMenu.ROWS : 0;
        refreshInstructionLabels();
    }

    private void tapInstruction(LogicBuild processor, int row) {
        switch (instructionView) {
            case program -> {
                if (row == 0 && instructionsChanged) {
                    applyProgram(processor, instructionProgram.source());
                } else if (row == 1 && instructionProgram.canInsert()) {
                    showInstructions(LogicView.palette);
                } else if (row >= 2 && row - 2 < instructionProgram.size()) {
                    focusedStatement = row - 2;
                    showInstructions(LogicView.statement);
                }
            }
            case palette -> {
                if (row < instructionProgram.paletteSize()
                        && instructionProgram.insert(instructionProgram.size(), row)) {
                    instructionsChanged = true;
                    focusedStatement = instructionProgram.size() - 1;
                    showInstructions(LogicView.program);
                }
            }
            case statement -> {
                switch (row) {
                    case 0 -> {
                        var source = new StringBuilder();
                        instructionProgram.get(focusedStatement).write(source);
                        if (source.length() > UpstreamProcessorConfiguration.APPLET_CHARACTERS) return;
                        String result = processorEditor.apply(source.toString());
                        if (result == null || result.equals(source.toString())
                                || !instructionProgram.replace(focusedStatement, result)) return;
                    }
                    case 1 -> {
                        if (!instructionProgram.duplicate(focusedStatement)) return;
                        focusedStatement++;
                    }
                    case 2 -> {
                        if (focusedStatement == 0) return;
                        instructionProgram.move(focusedStatement, focusedStatement - 1);
                        focusedStatement--;
                    }
                    case 3 -> {
                        if (focusedStatement + 1 >= instructionProgram.size()) return;
                        instructionProgram.move(focusedStatement, focusedStatement + 1);
                        focusedStatement++;
                    }
                    case 4 -> {
                        instructionProgram.remove(focusedStatement);
                        focusedStatement = Math.min(focusedStatement, instructionProgram.size() - 1);
                    }
                    default -> { return; }
                }
                instructionsChanged = true;
                showInstructions(LogicView.program);
            }
        }
    }

    private int instructionRows() {
        return switch (instructionView) {
            case program -> 2 + instructionProgram.size();
            case statement -> 5;
            case palette -> instructionProgram.paletteSize();
        };
    }

    private void refreshInstructionLabels() {
        refreshInstructionLabels(target.block.localizedName);
    }

    private void refreshInstructionLabels(String title) {
        var names = new String[UpstreamBuildMenu.ROWS + 1];
        Arrays.fill(names, "");
        names[0] = Strings.stripColors(title);
        for (int slot = 0; slot < UpstreamBuildMenu.ROWS; slot++) {
            int row = page * UpstreamBuildMenu.ROWS + slot;
            if (row >= instructionRows()) break;
            names[slot + 1] = switch (instructionView) {
                case program -> switch (row) {
                    case 0 -> instructionsChanged ? Core.bundle.get("editor.apply") : "";
                    case 1 -> instructionProgram.canInsert() ? Core.bundle.get("add") : "";
                    default -> (row - 2) + " " + instructionProgram.get(row - 2).localizedName();
                };
                case palette -> instructionProgram.paletteEntry(row).localizedName();
                case statement -> Core.bundle.get(switch (row) {
                    case 0 -> "edit";
                    case 1 -> "editor.copy";
                    case 2 -> "editor.moveup";
                    case 3 -> "editor.movedown";
                    default -> "remove";
                });
            };
        }
        labels = (String.join("\0", names) + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private void applyProgram(LogicBuild processor, String code) {
        validate();
        if (target != processor) return;
        if (processor.code.equals(code)) {
            close();
            return;
        }
        byte[] config = UpstreamProcessorConfiguration.encode(processor, code);
        if (config == null) {
            String error = Core.bundle.get("editor.errorload");
            if (instructionView == null) refreshProcessorLabels(error);
            else refreshInstructionLabels(error);
            return;
        }
        processor.configure(config);
        System.out.println("UPSTREAM_PROCESSOR_EDIT x=" + processor.tileX() + " y=" + processor.tileY()
            + " bytes=" + config.length);
        close();
    }

    private void refreshProcessorLabels(String title) {
        var names = new String[]{Strings.stripColors(title), Core.bundle.get("edit"),
            Core.bundle.get("editor.importfile"), Core.bundle.get("editor.exportfile"),
            Core.bundle.get("link", "Link"), Core.bundle.get("logic", "Logic"), ""};
        labels = (String.join("\0", names) + "\0").getBytes(StandardCharsets.UTF_8);
    }

    void validate() {
        if (target != null && (!target.isValid() || !Units.canInteract(player, target)
                || target.shouldHideConfigure(player))) close();
    }

    void close() {
        target.onConfigureClosed();
        target = null;
        linking = false;
        instructionProgram = null;
        instructionView = null;
        focusedStatement = 0;
    }

    private void refreshLabels() {
        if (instructionView != null) {
            refreshInstructionLabels();
            return;
        }
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

    boolean isOpen() { return target != null && !linking; }
    boolean isLinking() { return linking; }
    Building linkSource() { return linking ? target : null; }
    byte[] labels() { return labels; }
    int[] iconIds() { return iconIds; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() {
        int count = instructionView == null ? choices.size : instructionRows();
        return (page + 1) * UpstreamBuildMenu.ROWS < count;
    }
    boolean unitPlans() { return target instanceof UnitFactory.UnitFactoryBuild; }
    int selectedRow() { return target instanceof LogicBuild ? -1 : selectedIndex() - page * UpstreamBuildMenu.ROWS; }

    private int selectedIndex() {
        Object value = target.config();
        for (int index = 0; index < choices.size; index++) {
            if (Objects.equals(choices.get(index).value(), value)) return index;
        }
        return -1;
    }
}
