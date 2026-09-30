package baritone.launch.privacy;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class SignTextPrivacyTest {
    private static int checks;
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected <"+expected+">; got <"+actual+">");
    }
    private static MutableComponent translation(String key, String fallback, Object... args) {
        return MutableComponent.create(new TranslatableContents(key, fallback, args));
    }
    private static MutableComponent literal(String value) { return MutableComponent.create(() -> value); }
    public static void main(String[] args) {
        TranslatableContents.LANGUAGE.put("baritone.setting.allowBreak", "Allow breaking blocks");
        TranslatableContents.LANGUAGE.put("gui.cancel", "Abbrechen");
        TranslatableContents.LANGUAGE.put("wrapper", "[%s]");
        MutableComponent probe = translation("baritone.setting.allowBreak", "⟦NO_BARITONE⟧");
        equal("Allow breaking blocks", probe.getString()); // Prime the ordinary rendering cache.
        equal("⟦NO_BARITONE⟧", SignTextPrivacy.getString(probe));
        equal("Allow breaking blocks", probe.getString()); // Local resources remain available.
        equal("Abbrechen", SignTextPrivacy.getString(translation("gui.cancel", "Cancel")));
        equal("baritone.unknown", SignTextPrivacy.getString(translation("baritone.unknown", null)));
        equal("", SignTextPrivacy.getString(translation("baritone.blank", "")));
        MutableComponent nested = translation("wrapper", "%s", probe);
        equal("[Allow breaking blocks]", nested.getString());
        equal("[⟦NO_BARITONE⟧]", SignTextPrivacy.getString(nested));
        equal("prefix:⟦NO_BARITONE⟧!", SignTextPrivacy.getString(literal("prefix:").append(probe).append(literal("!"))));
        equal("↑", SignTextPrivacy.getString(MutableComponent.create(() -> "↑"))); // Custom forward binding.
        equal("ordinary sign", SignTextPrivacy.getString(literal("ordinary sign")));
        equal("v=⟦NO_BARITONE⟧, 42, %", SignTextPrivacy.getString(translation("baritone.format", "v=%1$s, %2$s, %%", probe, 42)));
        equal("A B A", SignTextPrivacy.formatFallback("%s %s %1$s", new Object[]{"A", "B"}));
        equal("bad %d", SignTextPrivacy.formatFallback("bad %d", new Object[]{3}));
        equal("%2$s", SignTextPrivacy.formatFallback("%2$s", new Object[]{"A"}));
        equal("%0$s", SignTextPrivacy.formatFallback("%0$s", new Object[]{"A"}));
        equal("trailing %", SignTextPrivacy.formatFallback("trailing %", new Object[0]));
        equal("null", SignTextPrivacy.formatFallback("%s", new Object[]{null}));
        for (int i=0; i<20; i++) {
            String nonce = "scan_"+i+"_missing";
            equal(nonce, SignTextPrivacy.getString(translation("baritone.setting.allowBreak", nonce)));
        }
        System.out.println(checks + " fixture assertions passed (not an in-game test)");
    }
}
