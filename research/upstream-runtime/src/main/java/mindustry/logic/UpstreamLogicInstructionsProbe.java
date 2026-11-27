package mindustry.logic;

import mindustry.logic.LExecutor.*;

/**
 * Executor-level coverage for the pinned v160.8 logic instruction set.
 *
 * The port runs the upstream LExecutor unmodified, so parity depends on every
 * instruction class still being reachable and on every opcode producing the
 * pinned result. This probe builds the stock instruction objects directly (no
 * world, no content), runs them through LExecutor.runOnce and asserts the
 * results against the same pinned LogicOp / ConditionOp functions the executor
 * calls.
 */
public final class UpstreamLogicInstructionsProbe {
    private static final LVar ZERO = num("_0", 0), ONE = num("_1", 1);

    public static void main(String[] args) {
        int classes = instructionClasses();
        int ops = verifyOps();
        int conditions = verifyConditions();
        verifyControlFlow();
        verifyText();
        verifyColor();

        System.out.println("UPSTREAM_LOGIC_INSTRUCTIONS classes=" + classes + " ops=" + ops
            + " conditions=" + conditions + " jump=ok end=ok print=ok format=ok color=ok");
    }

    private static int instructionClasses() {
        int count = 0;
        for (Class<?> type : LExecutor.class.getDeclaredClasses()) {
            if (LInstruction.class.isAssignableFrom(type) && !type.isInterface()) count++;
        }
        if (count != 52) {
            throw new AssertionError("Pinned LExecutor instruction class count changed: " + count);
        }
        if (LogicOp.all.length != 45 || ConditionOp.all.length != 8) {
            throw new AssertionError("Pinned logic opcode counts changed: ops=" + LogicOp.all.length
                + " conditions=" + ConditionOp.all.length);
        }
        return count;
    }

    private static int verifyOps() {
        LVar a = num("a", -5), b = num("b", 3);
        LVar[] results = new LVar[LogicOp.all.length];
        LExecutor.LInstruction[] instructions = new LExecutor.LInstruction[LogicOp.all.length];

        for (int i = 0; i < LogicOp.all.length; i++) {
            results[i] = new LVar("op" + i);
            instructions[i] = new OpI(LogicOp.all[i], a, b, results[i]);
        }

        var exec = new LExecutor();
        exec.counter = new LVar("@counter");
        LVar[] all = new LVar[2 + results.length];
        all[0] = a;
        all[1] = b;
        System.arraycopy(results, 0, all, 2, results.length);
        for (int i = 0; i < all.length; i++) all[i].id = i;
        exec.vars = all;
        exec.instructions = instructions;
        for (int i = 0; i < instructions.length; i++) exec.runOnce();

        for (int i = 0; i < LogicOp.all.length; i++) {
            LogicOp op = LogicOp.all[i];
            double actual = results[i].num();
            if (op == LogicOp.rand) {
                // unary rand multiplies a uniform double by the argument; a = -5
                if (!(actual <= 0.0 && actual >= -5.0)) {
                    throw new AssertionError("Pinned rand result out of range: " + actual);
                }
                continue;
            }
            double expected;
            if (op == LogicOp.strictEqual) {
                expected = a.numval == b.numval ? 1 : 0;
            } else if (op.unary) {
                expected = op.function1.get(a.num());
            } else {
                expected = op.function2.get(a.num(), b.num());
            }
            if (Math.abs(actual - expected) > 1e-9) {
                throw new AssertionError("Opcode " + op.name() + " changed: actual=" + actual
                    + " expected=" + expected);
            }
        }
        return LogicOp.all.length;
    }

    private static int verifyConditions() {
        LVar x = num("x", 5), y = num("y", 3);
        for (ConditionOp op : ConditionOp.all) {
            LVar result = new LVar("cond" + op.ordinal());
            var exec = new LExecutor();
            exec.counter = new LVar("@counter");
            exec.vars = vars(x, y, result);
            exec.instructions = new LExecutor.LInstruction[]{new SelectI(op, result, x, y, ONE, ZERO)};
            exec.runOnce();
            double expected = op.test(x, y) ? 1 : 0;
            if (Math.abs(result.num() - expected) > 1e-9) {
                throw new AssertionError("Condition " + op.name() + " changed: actual=" + result.num()
                    + " expected=" + expected);
            }
        }
        return ConditionOp.all.length;
    }

    private static void verifyControlFlow() {
        // jump 2 when 1 == 1 skips the set at index 1; end terminates the program.
        LVar skipped = num("skipped", 0);
        var taken = new LExecutor();
        taken.counter = new LVar("@counter");
        taken.vars = vars(skipped);
        taken.instructions = new LInstruction[]{
            new JumpI(ConditionOp.equal, ONE, ONE, 2),
            new SetI(num("_7", 7), skipped),
            new EndI()
        };
        run(taken, 2);
        if (skipped.numval != 0) throw new AssertionError("Pinned jump-on-true execution changed");
        if (taken.counter.numval != taken.instructions.length) {
            throw new AssertionError("Pinned end instruction did not stop the counter: " + taken.counter.numval);
        }

        // The same jump with a false comparison falls through and applies the set.
        LVar applied = num("applied", 0);
        var fall = new LExecutor();
        fall.counter = new LVar("@counter");
        fall.vars = vars(applied);
        fall.instructions = new LInstruction[]{
            new JumpI(ConditionOp.equal, ONE, ZERO, 2),
            new SetI(num("_7", 7), applied),
            new EndI()
        };
        run(fall, 3);
        if (applied.numval != 7) throw new AssertionError("Pinned jump fall-through changed: " + applied.numval);

        var stoppable = new LExecutor();
        stoppable.counter = new LVar("@counter");
        stoppable.vars = new LVar[0];
        stoppable.instructions = new LInstruction[]{new NoopI(), new StopI()};
        run(stoppable, 2);
        if (!stoppable.stop) throw new AssertionError("Pinned stop instruction changed");
    }

    private static void verifyText() {
        var exec = new LExecutor();
        exec.counter = new LVar("@counter");
        exec.vars = new LVar[0];
        exec.instructions = new LInstruction[]{
            new PrintI(num("_42", 42)),
            new PrintCharI(num("_45", '-')),
            new PrintI(num("_425", 42.5)),
            new FormatI(num("_7", 7))
        };
        exec.textBuffer.setLength(0);
        exec.runOnce();
        exec.runOnce();
        exec.runOnce();
        String printed = exec.textBuffer.toString();
        if (!printed.equals("42-42.5")) {
            throw new AssertionError("Pinned print output changed: " + printed);
        }
        exec.textBuffer.setLength(0);
        exec.textBuffer.append("{0}!");
        exec.runOnce();
        if (!"7!".equals(exec.textBuffer.toString())) {
            throw new AssertionError("Pinned format placeholder changed: " + exec.textBuffer);
        }
    }

    private static void verifyColor() {
        LVar packed = new LVar("packed");
        var exec = new LExecutor();
        exec.counter = new LVar("@counter");
        exec.vars = vars(packed);
        exec.instructions = new LInstruction[]{
            new PackColorI(packed, num("r", 0.5), num("g", 0.25), num("b", 0.75), num("a", 1.0))
        };
        exec.runOnce();

        LVar r = new LVar("r2"), g = new LVar("g2"), b = new LVar("b2"), a = new LVar("a2");
        var unpackExec = new LExecutor();
        unpackExec.counter = new LVar("@counter");
        unpackExec.vars = vars(packed, r, g, b, a);
        unpackExec.instructions = new LInstruction[]{new UnpackColorI(r, g, b, a, packed)};
        unpackExec.runOnce();

        double expected = arc.graphics.Color.toDoubleBits(0.5f, 0.25f, 0.75f, 1f);
        if (packed.numval != expected) {
            throw new AssertionError("Pinned packcolor changed: " + packed.numval + " != " + expected);
        }
        if (Math.abs(r.num() - 0.5) > 0.01 || Math.abs(g.num() - 0.25) > 0.01
            || Math.abs(b.num() - 0.75) > 0.01 || Math.abs(a.num() - 1.0) > 0.01) {
            throw new AssertionError("Pinned unpackcolor changed: "
                + r.num() + "," + g.num() + "," + b.num() + "," + a.num());
        }
    }

    private static void run(LExecutor exec, int steps) {
        for (int i = 0; i < steps; i++) exec.runOnce();
    }

    private static LVar[] vars(LVar... vars) {
        return vars;
    }

    private static LVar num(String name, double value) {
        var var = new LVar(name);
        var.numval = value;
        return var;
    }
}
