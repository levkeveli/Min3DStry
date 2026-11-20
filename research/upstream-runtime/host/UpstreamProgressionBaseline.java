import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.core.ContentLoader;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.type.ItemStack;
import mindustry.type.SectorPreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Host-only reference for the pinned upstream campaign structure.
 *
 * createBaseContent() loads SerpuloTechTree/ErekirTechTree and SectorPresets;
 * this dumps their records without a custom fixture or save format.
 */
class UpstreamProgressionBaseline {
    public static void main(String[] args) {
        Vars.headless = true;
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();

        List<String> records = new ArrayList<>();
        for (var node : TechTree.all) {
            String requirements = requirements(node.requirements);
            records.add(String.format(
                Locale.ROOT,
                "UPSTREAM_TECH type=%s id=%d name=%s depth=%d parent=%s root=%s requirements=%s",
                node.content.getContentType().name(),
                node.content.id,
                node.content.name,
                node.depth,
                node.parent == null ? "-" : node.parent.content.name,
                node.rootNode == null ? "-" : node.rootNode.name,
                requirements));
        }
        Collections.sort(records);

        List<String> sectorRecords = new ArrayList<>();
        for (Content content : Vars.content.getBy(ContentType.sector)) {
            if (!(content instanceof SectorPreset preset)) {
                continue;
            }
            sectorRecords.add(String.format(
                Locale.ROOT,
                "UPSTREAM_SECTOR id=%d name=%s planet=%s sector=%d capture=%d difficulty=%.1f last=%s",
                preset.id,
                preset.name,
                preset.planet == null ? "-" : preset.planet.name,
                preset.sector == null ? -1 : preset.sector.id,
                preset.captureWave,
                preset.difficulty,
                preset.isLastSector));
        }
        Collections.sort(sectorRecords);
        records.addAll(sectorRecords);

        MessageDigest manifest = sha256();
        for (String record : records) {
            System.out.println(record);
            manifest.update(record.getBytes(StandardCharsets.UTF_8));
            manifest.update((byte)'\n');
        }
        System.out.printf(
            "UPSTREAM_PROGRESSION tech_nodes=%d tech_roots=%d sectors=%d manifest_sha256=%s%n",
            TechTree.all.size,
            TechTree.roots.size,
            sectorRecords.size(),
            HexFormat.of().formatHex(manifest.digest()));
    }

    private static String requirements(ItemStack[] stacks) {
        if (stacks == null || stacks.length == 0) {
            return "-";
        }
        StringBuilder result = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(stack.item.name).append(':').append(stack.amount);
        }
        return result.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
