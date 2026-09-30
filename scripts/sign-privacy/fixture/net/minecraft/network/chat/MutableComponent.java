package net.minecraft.network.chat;
import java.util.ArrayList;
import java.util.List;
public final class MutableComponent implements Component {
    private final ComponentContents contents;
    private final List<Component> siblings = new ArrayList<>();
    private MutableComponent(ComponentContents contents) { this.contents = contents; }
    public static MutableComponent create(ComponentContents contents) { return new MutableComponent(contents); }
    public ComponentContents getContents() { return contents; }
    public List<Component> getSiblings() { return siblings; }
    public MutableComponent plainCopy() { return create(contents); }
    public MutableComponent append(Component component) { siblings.add(component); return this; }
    public String getString() {
        StringBuilder text = new StringBuilder(contents.text());
        for (Component sibling : siblings) text.append(sibling.getString());
        return text.toString();
    }
}
