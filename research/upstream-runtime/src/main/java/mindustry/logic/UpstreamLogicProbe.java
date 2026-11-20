package mindustry.logic;

public final class UpstreamLogicProbe {
    public static void main(String[] args) {
        int quotient = (int)LogicOp.idiv.function2.get(-5, 2);
        int remainder = (int)LogicOp.emod.function2.get(-5, 3);
        int bitwise = (int)LogicOp.xor.function2.get(5, 3);
        if (quotient != -3 || remainder != 1 || bitwise != 6) {
            throw new AssertionError("Upstream LogicOp results changed");
        }
        System.out.println("UPSTREAM_LOGIC quotient=" + quotient +
            " remainder=" + remainder + " xor=" + bitwise);
    }
}
