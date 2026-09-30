package net.minecraft.world.phys;
/** Only the vector surface used by the actual KeyboardInput bytecode and Baritone. */
public final class Vec2 {
    public static final Vec2 ZERO = new Vec2(0, 0);
    public final float x, y;
    public Vec2(float x, float y) { this.x = x; this.y = y; }
    public Vec2 normalized() {
        float length = (float) Math.sqrt(x * x + y * y);
        return length < 0.0001F ? ZERO : new Vec2(x / length, y / length);
    }
}
