package net.minecraft.world.entity.player;
public record Input(boolean forward, boolean backward, boolean left, boolean right,
                    boolean jump, boolean shift, boolean sprint) {
    public static final Input EMPTY = new Input(false, false, false, false, false, false, false);
}
