/* SPDX-License-Identifier: LGPL-3.0-or-later */
import baritone.api.utils.input.Input;
import baritone.launch.privacy.SignTextPrivacy;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.KeybindResolver;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec2;
import sun.misc.Unsafe;

/** Real game classes and the actual optimized candidate JAR; no component/input/math stubs. */
public final class ActualRuntimeTest {
    private static int assertions;
    private static void equal(String expected, String actual) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected <"+expected+">, got <"+actual+">");
    }
    private static Unsafe unsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return (Unsafe) f.get(null);
    }
    public static void main(String[] args) throws Exception {
        Map<String, String> table = new HashMap<>();
        table.put("baritone.setting.allowBreak", "PRIVATE_BARITONE_TRANSLATION");
        table.put("wrapper", "[%s]"); table.put("gui.cancel", "Abbrechen");
        Language.inject(new Language() {
            public String getOrDefault(String key, String fallback) { return table.getOrDefault(key, fallback); }
            public boolean has(String key) { return table.containsKey(key); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
        KeybindResolver.setKeyResolver(key -> () -> Component.literal(key.equals("key.forward") ? "↑" : key));
        Component probe = Component.translatableWithFallback("baritone.setting.allowBreak", "⟦NO_BARITONE⟧");
        equal("PRIVATE_BARITONE_TRANSLATION", probe.getString());
        equal("⟦NO_BARITONE⟧", SignTextPrivacy.getString(probe));
        equal("PRIVATE_BARITONE_TRANSLATION", probe.getString());
        Component nested = Component.translatable("wrapper", probe);
        equal("[PRIVATE_BARITONE_TRANSLATION]", nested.getString());
        equal("[⟦NO_BARITONE⟧]", SignTextPrivacy.getString(nested));
        equal("prefix:⟦NO_BARITONE⟧!", SignTextPrivacy.getString(Component.literal("prefix:").append(probe).append("!")));
        equal("Abbrechen", SignTextPrivacy.getString(Component.translatable("gui.cancel")));
        equal("↑", SignTextPrivacy.getString(Component.keybind("key.forward")));
        equal("key.unknown", SignTextPrivacy.getString(Component.keybind("key.unknown")));
        equal("ordinary sign", SignTextPrivacy.getString(Component.literal("ordinary sign")));
        equal("baritone.unknown", SignTextPrivacy.getString(Component.translatable("baritone.unknown")));
        equal("", SignTextPrivacy.getString(Component.translatableWithFallback("baritone.blank", "")));
        String[] templates = {"%s", "%2$s %1$s", "%s %s %1$s", "%%", "invalid %d", "%0$s", "%3$s", "trailing %", "a%_b", "%2147483648$s", "%1$%"};
        for (String template : templates) {
            Component vanilla = Component.translatableWithFallback("unknown.key", template, "A", "B");
            Component baritone = Component.translatableWithFallback("baritone.setting.allowBreak", template, "A", "B");
            equal(vanilla.getString(), SignTextPrivacy.getString(baritone));
        }
        for (int i=0; i<100; i++) {
            String nonce = "scan_" + i + "_missing";
            equal(nonce, SignTextPrivacy.getString(Component.translatableWithFallback("baritone.setting.allowBreak", nonce)));
        }
        System.out.println("Real Minecraft components: " + assertions + " assertions passed");

        // Allocate only the control objects without a graphics/client/world constructor.
        // All methods under comparison, vector math, and key-state records are real game/release classes.
        Unsafe u = unsafe();
        Options options = (Options) u.allocateInstance(Options.class);
        String[] fields = {"keyUp", "keyDown", "keyLeft", "keyRight", "keyJump", "keyShift", "keySprint"};
        Input[] controls = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.JUMP, Input.SNEAK, Input.SPRINT};
        KeyMapping[] mappings = new KeyMapping[7];
        for (int i=0; i<7; i++) {
            mappings[i] = (KeyMapping) u.allocateInstance(KeyMapping.class);
            Field field = Options.class.getField(fields[i]); field.setAccessible(true); field.set(options, mappings[i]);
        }
        Class<?> pathingClass = Class.forName("baritone.fp");
        Field handlerField = pathingClass.getDeclaredFields()[0];
        Class<?> handlerClass = handlerField.getType();
        Object handler = u.allocateInstance(handlerClass);
        for (Field f : handlerClass.getDeclaredFields()) {
            if (f.getType() == Map.class) { f.setAccessible(true); f.set(handler, new HashMap<Input,Boolean>()); }
        }
        Method force = handlerClass.getMethod("setInputForceState", Input.class, boolean.class);
        Constructor<?> constructor = pathingClass.getDeclaredConstructor(handlerClass); constructor.setAccessible(true);
        ClientInput pathing = (ClientInput) constructor.newInstance(handler);
        KeyboardInput vanilla = new KeyboardInput(options);
        for (int mask=0; mask<128; mask++) {
            for (int bit=0; bit<7; bit++) {
                boolean down = (mask & (1 << bit)) != 0;
                mappings[bit].setDown(down); force.invoke(handler, controls[bit], down);
            }
            vanilla.tick(); pathing.tick();
            Vec2 expected = vanilla.getMoveVector(), actual = pathing.getMoveVector();
            if (Math.abs(expected.x - actual.x) > 0.000001F || Math.abs(expected.y - actual.y) > 0.000001F || !vanilla.keyPresses.equals(pathing.keyPresses))
                throw new AssertionError("Movement mask " + mask + ": vanilla="+expected.x+","+expected.y+" candidate="+actual.x+","+actual.y);
        }
        System.out.println("Real candidate movement class: all 128 input combinations match real KeyboardInput/Vec2/Input");
    }
}
