package net.minecraft.network.chat.contents;
import net.minecraft.network.chat.*;
import java.util.HashMap;
import java.util.Map;
public class TranslatableContents implements ComponentContents {
    public static final Map<String,String> LANGUAGE = new HashMap<>();
    private final String key, fallback;
    private final Object[] args;
    private String cached;
    public TranslatableContents(String key, String fallback, Object[] args) {
        this.key=key; this.fallback=fallback; this.args=args;
    }
    public String getKey() { return key; }
    public String getFallback() { return fallback; }
    public Object[] getArgs() { return args; }
    public String text() {
        if (cached == null) {
            Object[] rendered = args.clone();
            for (int i=0; i<rendered.length; i++) if (rendered[i] instanceof Component c) rendered[i]=c.getString();
            String format = LANGUAGE.getOrDefault(key, fallback == null ? key : fallback);
            try { cached = String.format(format, rendered); }
            catch (java.util.IllegalFormatException e) { cached = format; }
        }
        return cached;
    }
}
