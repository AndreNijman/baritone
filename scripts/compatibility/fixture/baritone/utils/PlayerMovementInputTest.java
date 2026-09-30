package baritone.utils;
import baritone.api.utils.input.Input;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;

/** Differential test against Minecraft 26.3's actual KeyboardInput bytecode with narrow API fixtures. */
public final class PlayerMovementInputTest {
    public static void main(String[] args) {
        Options options = new Options();
        KeyMapping[] keys = {options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint};
        Input[] overrides = {Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT,
                Input.JUMP, Input.SNEAK, Input.SPRINT};
        InputOverrideHandler handler = new InputOverrideHandler();
        KeyboardInput vanilla = new KeyboardInput(options);
        PlayerMovementInput pathing = new PlayerMovementInput(handler);
        for (int mask = 0; mask < 128; mask++) {
            for (int bit = 0; bit < 7; bit++) {
                boolean pressed = (mask & (1 << bit)) != 0;
                keys[bit].setDown(pressed);
                handler.setInputForceState(overrides[bit], pressed);
            }
            vanilla.tick();
            pathing.tick();
            Vec2 expected = vanilla.getMoveVector(), actual = pathing.getMoveVector();
            if (Math.abs(expected.x - actual.x) > 0.000001F || Math.abs(expected.y - actual.y) > 0.000001F
                    || !vanilla.keyPresses.equals(pathing.keyPresses)) {
                throw new AssertionError("Input mask " + mask + ": vanilla=(" + expected.x + "," + expected.y
                        + ") Baritone=(" + actual.x + "," + actual.y + ")");
            }
        }
        System.out.println("128 input combinations match actual Minecraft 26.3 KeyboardInput bytecode (input/math API fixtures)");
    }
}
