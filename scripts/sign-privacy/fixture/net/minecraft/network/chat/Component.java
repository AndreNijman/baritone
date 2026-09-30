package net.minecraft.network.chat;
import java.util.List;
public interface Component {
    ComponentContents getContents();
    List<Component> getSiblings();
    MutableComponent plainCopy();
    String getString();
}
