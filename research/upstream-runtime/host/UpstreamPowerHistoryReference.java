import arc.util.Time;
import mindustry.world.blocks.power.PowerGraph;

/** Compares stock and staged rolling power statistics over several window wraps. */
public final class UpstreamPowerHistoryReference {
    public static void main(String[] args) {
        var graph = new PowerGraph(false);
        print(graph);
        for (int step = 0; step < 181; step++) {
            Time.delta = 0.5f + (step % 5) * 0.25f;
            graph.transferPower((step % 13 - 6) * 1.25f);
            graph.update();
            if (graph.hasPowerBalanceSamples() != (step >= 59)) {
                throw new AssertionError("Invalid stock history readiness at sample " + step);
            }
            print(graph);
        }
    }

    private static void print(PowerGraph graph) {
        System.out.printf("%s %08x %08x %08x%n", graph.hasPowerBalanceSamples(),
            Float.floatToIntBits(graph.getPowerBalance()),
            Float.floatToIntBits(graph.getLastScaledPowerIn()),
            Float.floatToIntBits(graph.getLastScaledPowerOut()));
    }
}
