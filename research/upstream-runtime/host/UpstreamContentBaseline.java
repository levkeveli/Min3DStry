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
            for (Content content : Vars.content.getBy(type)) {
                String name = content instanceof MappableContent mapped ? mapped.name : "-";
                System.out.print("UPSTREAM_CONTENT\t" + type.name() + "\t" + content.id + "\t" + name + "\n");
            }
        }
    }
}
