import arc.math.Mathf;
import arc.struct.Seq;
import java.util.Arrays;
import mindustry.content.TechTree.TechNode;
import mindustry.ui.layout.BranchTreeLayout;
import mindustry.ui.layout.BranchTreeLayout.TreeLocation;
import mindustry.ui.layout.TreeLayout.TreeNode;

/**
 * Stock v160.8 ResearchDialog layout and visibility adapted for the handheld UI.
 * Upstream: core/src/mindustry/ui/dialogs/ResearchDialog.java at 19a0f40.
 */
final class UpstreamCampaignResearchTree {
    private static final float nodeSize = 60f;
    private static final float gap = 20f;

    static final class Node extends TreeNode<Node> {
        final TechNode tech;

        Node(TechNode tech, Node parent) {
            this.tech = tech;
            this.parent = parent;
            width = height = nodeSize;
            children = new Node[tech.children.size];
            for (int i = 0; i < children.length; i++) {
                children[i] = new Node(tech.children.get(i), this);
            }
        }
    }

    final Node root;
    final Seq<Node> visible = new Seq<>();

    UpstreamCampaignResearchTree(TechNode tech) {
        root = new Node(tech, null);
        Node[] children = root.children;
        int split = (children.length + 1) / 2;
        Node[] top = Arrays.copyOfRange(children, 0, split);
        root.children = top;
        layout(TreeLocation.top, root);
        float firstY = root.y;
        if (split < children.length) {
            root.children = Arrays.copyOfRange(children, split, children.length);
            layout(TreeLocation.bottom, root);
            for (Node node : top) shift(node, root.y - firstY);
        }
        root.children = children;
        refresh();
    }

    void refresh() {
        visible.clear();
        addVisible(root);
    }

    static float scrollZoom(float zoom, int scroll) {
        // ResearchDialog scrolls relative to the current scale.
        return Mathf.clamp(zoom - scroll / 10f * zoom, 0.25f, 1f);
    }

    int horizontalIndex(Node node, int direction) {
        int best = -1;
        float distance = Float.POSITIVE_INFINITY;
        for (int i = 0; i < visible.size; i++) {
            Node candidate = visible.get(i);
            float forward = (candidate.x - node.x) * direction;
            if (forward <= 0f) continue;
            float score = forward + 2f * Math.abs(candidate.y - node.y);
            if (score < distance) {
                distance = score;
                best = i;
            }
        }
        return best;
    }

    private void addVisible(Node node) {
        visible.add(node);
        boolean unlocked = node.tech.content.unlockedHost();
        for (Node child : node.children) {
            if (unlocked || child.tech.content.unlockedHost()) addVisible(child);
        }
    }

    private static void layout(TreeLocation location, Node root) {
        var layout = new BranchTreeLayout();
        layout.rootLocation = location;
        layout.gapBetweenLevels = gap;
        layout.gapBetweenNodes = gap;
        layout.layout(root);
    }

    private static void shift(Node node, float dy) {
        node.y += dy;
        for (Node child : node.children) shift(child, dy);
    }
}
