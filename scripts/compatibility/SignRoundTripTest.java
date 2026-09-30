/* SPDX-License-Identifier: LGPL-3.0-or-later */
import com.mojang.blaze3d.platform.TextInputManager;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.KeybindResolver;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import sun.misc.Unsafe;

/** Actual transformed editor constructor/removal and actual packet codec, with no graphics/network. */
public final class SignRoundTripTest {
    private static final class HeadlessScreen extends AbstractSignEditScreen {
        HeadlessScreen(SignBlockEntity sign, SignTextSlot slot, boolean filtered) { super(sign, slot, filtered); }
        protected void extractSignBackground(GuiGraphicsExtractor graphics) {}
        protected Vector3fc getSignTextScale() { return new Vector3f(1); }
        protected float getSignYOffset() { return 0; }
    }
    private static final class CaptureListener extends ClientPacketListener {
        private ServerboundSignUpdatePacket packet;
        private int sends;
        private CaptureListener() { super(null, null, null); }
        @Override public void send(Packet<?> packet) {
            if (!(packet instanceof ServerboundSignUpdatePacket sign)) throw new AssertionError("Unexpected packet");
            this.packet = sign; sends++;
        }
    }
    private static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static List<Component> lines(String fallback) {
        return List.of(Component.literal("ordinary"), Component.translatableWithFallback("baritone.setting.allowBreak", fallback), Component.translatableWithFallback("missing.mod", "missing"), Component.keybind("key.forward"));
    }
    public static void main(String[] args) throws Exception {
        boolean control = args.length > 0 && args[0].equals("--upstream-control");
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field uf = Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
        Unsafe unsafe = (Unsafe) uf.get(null);
        Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Minecraft.class);
        set(Minecraft.class, null, "instance", minecraft);
        set(Minecraft.class, minecraft, "gameDirectory", new java.io.File("/scratch/game"));
        set(net.minecraft.util.thread.BlockableEventLoop.class, minecraft, "pendingRunnables", new java.util.concurrent.ConcurrentLinkedQueue<Runnable>());
        set(Minecraft.class, minecraft, "textInputManager", new TextInputManager(null));
        LocalPlayer player = (LocalPlayer) unsafe.allocateInstance(LocalPlayer.class);
        CaptureListener listener = (CaptureListener) unsafe.allocateInstance(CaptureListener.class);
        set(LocalPlayer.class, player, "connection", listener);
        minecraft.player = player;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        for (String command : new String[]{"help", "goto", "stop", "mine", "build", "follow"}) {
            if (baritone.getCommandManager().getCommand(command) == null) throw new AssertionError("Missing command: " + command);
        }
        if (baritone.getPathingBehavior() == null || baritone.getCustomGoalProcess() == null || baritone.getInputOverrideHandler() == null)
            throw new AssertionError("Baritone core initialization failed");
        System.out.println("Baritone API initializes; help/goto/stop/mine/build/follow commands and core pathing/input processes are registered");
        Language.inject(new Language() {
            public String getOrDefault(String key, String fallback) {
                return key.equals("baritone.setting.allowBreak") ? "PRIVATE_TRANSLATION" : fallback;
            }
            public boolean has(String key) { return key.equals("baritone.setting.allowBreak"); }
            public boolean isDefaultRightToLeft() { return false; }
            public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
        KeybindResolver.setKeyResolver(key -> () -> Component.literal(key.equals("key.forward") ? "↑" : key));
        int rounds = 0;
        for (SignTextSlot slot : SignTextSlot.values()) for (boolean filtered : new boolean[]{false, true}) {
            for (int nonce=0; nonce<25; nonce++) {
                BlockPos pos = new BlockPos(7, 90, -12);
                String fallback = "probe_" + nonce + (filtered ? "_filtered" : "_plain");
                SignBlockEntity sign = new SignBlockEntity(pos, Blocks.OAK_SIGN.defaultBlockState());
                // Populate the received block-entity data without requiring a world/update notification.
                set(SignBlockEntity.class, sign, slot == SignTextSlot.FRONT ? "frontText" : "backText",
                    new SignText(lines("probe_" + nonce + "_plain"), lines("probe_" + nonce + "_filtered"), DyeColor.BLACK, false));
                HeadlessScreen screen = new HeadlessScreen(sign, slot, filtered);
                int before = listener.sends;
                screen.removed();
                if (listener.sends != before + 1) throw new AssertionError("Expected one sign update");
                ServerboundSignUpdatePacket sent = listener.packet;
                if (!sent.pos().equals(pos) || sent.slot() != slot || !sent.lines().equals(List.of("ordinary", control ? "PRIVATE_TRANSLATION" : fallback, "missing", "↑"))) throw new AssertionError("Incorrect sign response: " + sent);
                ByteBuf bytes = Unpooled.buffer();
                try {
                    ServerboundSignUpdatePacket.STREAM_CODEC.encode(bytes, sent);
                    ServerboundSignUpdatePacket decoded = ServerboundSignUpdatePacket.STREAM_CODEC.decode(bytes);
                    if (!decoded.equals(sent) || bytes.isReadable()) throw new AssertionError("Packet codec round trip failed");
                } finally { bytes.release(); }
                rounds++;
            }
        }
        System.out.println((control ? "Unpatched positive control (translation leaks)" : "Patched editor (fallback returned)") + ": " + rounds + " constructor/removal/packet-codec round trips passed (both sides and filtered states)");
    }
}
