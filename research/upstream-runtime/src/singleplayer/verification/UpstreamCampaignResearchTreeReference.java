import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.TechTree.TechNode;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;

/** Checks coordinate fingerprints from the stock v160.8 ResearchDialog at node size 60. */
public final class UpstreamCampaignResearchTreeReference {
    public static void main(String[] args) {
        Core.settings = new Settings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        verify(Planets.serpulo.techTree, 14, 223, 0xbb55e3cbad7dc687L);
        verify(Planets.erekir.techTree, 22, 152, 0x9eb10b7beac5c226L);
    }

    private static void verify(TechNode tech, int fresh, int total, long expectedLayout) {
        var tree = new UpstreamCampaignResearchTree(tech);
        if (tree.visible.size != fresh || layoutFingerprint(tree.root, 0xcbf29ce484222325L) != expectedLayout) {
            throw new AssertionError("Fresh research tree differs from stock " + tech.name);
        }
        for (var node : tree.visible) {
            for (int direction : new int[]{-1, 1}) {
                int next = tree.horizontalIndex(node, direction);
                if (next >= 0 && (tree.visible.get(next).x - node.x) * direction <= 0f) {
                    throw new AssertionError("Research navigation selected a node in the opposite direction");
                }
            }
        }
        reveal(tech);
        tree.refresh();
        if (tree.visible.size != total || layoutFingerprint(tree.root, 0xcbf29ce484222325L) != expectedLayout) {
            throw new AssertionError("Research changed stock coordinates or failed to reveal nodes " + tech.name);
        }
        System.out.println("UPSTREAM_RESEARCH_TREE_REFERENCE tree=" + tech.name + " fresh=" + fresh + " total=" + total);
    }

    private static long layoutFingerprint(UpstreamCampaignResearchTree.Node node, long hash) {
        hash = (hash ^ Float.floatToIntBits(node.x)) * 0x100000001b3L;
        hash = (hash ^ Float.floatToIntBits(node.y)) * 0x100000001b3L;
        hash = (hash ^ node.children.length) * 0x100000001b3L;
        for (var child : node.children) hash = layoutFingerprint(child, hash);
        return hash;
    }

    private static void reveal(TechNode node) {
        node.content.unlock();
        for (var child : node.children) reveal(child);
    }
}
