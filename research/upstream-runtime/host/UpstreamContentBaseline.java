import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;

public final class UpstreamContentBaseline {
    public static void main(String[] args) {
        Vars.headless = true;
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();

        for (ContentType type : ContentType.values()) {
            int expectedId = 0;
            for (Content content : Vars.content.getBy(type)) {
                if (content.id != expectedId || Vars.content.getByID(type, expectedId) != content) {
                    throw new IllegalStateException("Unexpected ID " + content.id + " for " + type
                        + " at index " + expectedId);
                }
                expectedId++;
                String name = content instanceof MappableContent mapped ? mapped.name : "-";
                System.out.print("UPSTREAM_CONTENT\t" + type.name() + "\t" + content.id + "\t" + name + "\n");
            }
        }
    }
}
