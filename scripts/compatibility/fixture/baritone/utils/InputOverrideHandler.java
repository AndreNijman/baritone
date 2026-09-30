package baritone.utils;
import baritone.api.utils.input.Input;
import java.util.EnumSet;
public final class InputOverrideHandler {
    private final EnumSet<Input> forced = EnumSet.noneOf(Input.class);
    public boolean isInputForcedDown(Input input) { return forced.contains(input); }
    public void setInputForceState(Input input, boolean state) {
        if (state) forced.add(input); else forced.remove(input);
    }
}
