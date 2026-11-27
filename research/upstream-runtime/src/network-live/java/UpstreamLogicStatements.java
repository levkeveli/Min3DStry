import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.gen.LogicIO;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LExecutor;
import mindustry.logic.LStatement;
import mindustry.logic.LStatements.InvalidStatement;
import mindustry.logic.LStatements.JumpStatement;

/** Original statement objects and jump destinations for the handheld instruction editor. */
final class UpstreamLogicStatements {
    private final Seq<LStatement> statements;
    private final boolean privileged;
    private final boolean logicUnitControl;
    private final Seq<LStatement> palette = new Seq<>();

    UpstreamLogicStatements(String code, boolean privileged, boolean logicUnitControl) {
        this.privileged = privileged;
        this.logicUnitControl = logicUnitControl;
        statements = LAssembler.read(code, privileged);
        statements.truncate(LExecutor.maxInstructions);
        for (var factory : LogicIO.allStatements) {
            var statement = factory.get();
            if (available(statement)) palette.add(statement);
        }
    }

    private boolean available(LStatement statement) {
        return !(statement instanceof InvalidStatement) && !statement.hidden()
            && (!statement.privileged() || privileged) && (!statement.nonPrivileged() || !privileged)
            && (privileged || logicUnitControl || statement.category() != LCategory.unit);
    }

    int size() {
        return statements.size;
    }

    LStatement get(int row) {
        return statements.get(row);
    }

    int paletteSize() {
        return palette.size;
    }

    LStatement paletteEntry(int row) {
        return palette.get(row);
    }

    boolean canInsert() {
        return statements.size < LExecutor.maxInstructions;
    }

    boolean insert(int row, int paletteRow) {
        if (!canInsert()) return false;
        var statement = palette.get(paletteRow).copy();
        if (statement == null) return false;
        if (statement instanceof JumpStatement jump) jump.destIndex = -1;
        var targets = captureTargets();
        statements.insert(row, statement);
        restoreTargets(targets);
        return true;
    }

    boolean duplicate(int row) {
        if (!canInsert()) return false;
        var source = statements.get(row);
        var copied = source.copy();
        if (copied == null) return false;
        var targets = captureTargets();
        if (source instanceof JumpStatement original && copied instanceof JumpStatement copy) {
            var destination = targets.get(original);
            if (destination != null) targets.put(copy, destination);
        }
        statements.insert(row + 1, copied);
        restoreTargets(targets);
        return true;
    }

    void move(int from, int to) {
        if (from == to) return;
        var targets = captureTargets();
        var statement = statements.remove(from);
        statements.insert(to, statement);
        restoreTargets(targets);
    }

    void remove(int row) {
        var targets = captureTargets();
        statements.remove(row);
        restoreTargets(targets);
    }

    boolean replace(int row, String editableSource) {
        Seq<LStatement> parsed;
        try {
            parsed = LAssembler.read(editableSource, privileged);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (parsed.size != 1 || !available(parsed.first())) return false;
        statements.set(row, parsed.first());
        return true;
    }

    String source() {
        return LAssembler.write(statements);
    }

    private ObjectMap<JumpStatement, LStatement> captureTargets() {
        var targets = new ObjectMap<JumpStatement, LStatement>();
        for (var statement : statements) {
            if (statement instanceof JumpStatement jump
                    && jump.destIndex >= 0 && jump.destIndex < statements.size) {
                targets.put(jump, statements.get(jump.destIndex));
            }
        }
        return targets;
    }

    private void restoreTargets(ObjectMap<JumpStatement, LStatement> targets) {
        for (var statement : statements) {
            if (statement instanceof JumpStatement jump) {
                jump.destIndex = statements.indexOf(targets.get(jump), true);
            }
        }
    }
}
