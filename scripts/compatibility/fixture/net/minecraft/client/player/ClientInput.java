package net.minecraft.client.player;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
public class ClientInput {
    public Input keyPresses = Input.EMPTY;
    protected Vec2 moveVector = Vec2.ZERO;
    public void tick() {}
    public Vec2 getMoveVector() { return moveVector; }
}
