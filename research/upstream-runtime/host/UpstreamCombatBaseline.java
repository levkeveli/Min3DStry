import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.entities.bullet.BulletType;
import mindustry.type.UnitType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Host-only reference for pinned upstream combat content metadata.
 *
 * This reads UnitType/BulletType records after ContentLoader.createBaseContent
 * and records the original IDs and core fields. It does not implement combat.
 */
class UpstreamCombatBaseline {
    public static void main(String[] args) {
        Vars.headless = true;
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();

        List<String> records = new ArrayList<>();
        int units = 0;
        for (Content content : Vars.content.getBy(ContentType.unit)) {
            if (!(content instanceof UnitType unit)) {
                continue;
            }
            records.add(String.format(
                Locale.ROOT,
                "UPSTREAM_UNIT id=%d name=%s health=%.2f armor=%.2f speed=%.2f hitSize=%.2f flying=%s range=%.2f maxRange=%.2f",
                unit.id,
                unit.name,
                unit.health,
                unit.armor,
                unit.speed,
                unit.hitSize,
                unit.flying,
                unit.range,
                unit.maxRange));
            units++;
        }

        int bullets = 0;
        for (Content content : Vars.content.getBy(ContentType.bullet)) {
            if (!(content instanceof BulletType bullet)) {
                continue;
            }
            String type = bullet.getClass().getSimpleName();
            if (type.isEmpty() && bullet.getClass().getSuperclass() != null) {
                type = bullet.getClass().getSuperclass().getSimpleName();
            }
            if (type.isEmpty()) {
                type = "anonymous";
            }
            records.add(String.format(
                Locale.ROOT,
                "UPSTREAM_BULLET id=%d type=%s damage=%.2f speed=%.4f lifetime=%.2f hitSize=%.2f pierce=%s pierceCap=%d splashDamage=%.2f collides=%s",
                bullet.id,
                type,
                bullet.damage,
                bullet.speed,
                bullet.lifetime,
                bullet.hitSize,
                bullet.pierce,
                bullet.pierceCap,
                bullet.splashDamage,
                bullet.collides));
            bullets++;
        }

        MessageDigest manifest = sha256();
        for (String record : records) {
            System.out.println(record);
            manifest.update(record.getBytes(StandardCharsets.UTF_8));
            manifest.update((byte)'\n');
        }
        System.out.printf(
            "UPSTREAM_COMBAT units=%d bullets=%d manifest_sha256=%s%n",
            units,
            bullets,
            HexFormat.of().formatHex(manifest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
