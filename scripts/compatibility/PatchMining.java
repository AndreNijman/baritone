/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Align the pinned optimized mining helper with Minecraft 26.3's normal swing path. */
public final class PatchMining implements Opcodes {
    private static final String OWNER = "baritone/fj";
    private static final String CTX = "baritone/api/utils/IPlayerContext";
    private static final String PLAYER = "net/minecraft/client/player/LocalPlayer";
    private static final String HAND = "net/minecraft/world/InteractionHand";
    private static final String ANIMATION = "net/minecraft/world/item/component/SwingAnimation";
    private static final String PUNCH = "net/minecraft/network/protocol/game/ServerboundPunchPacket";
    public static void main(String[] args) throws Exception {
        byte[] original;
        try (ZipFile jar = new ZipFile(args[0])) { original = jar.getInputStream(jar.getEntry(OWNER + ".class")).readAllBytes(); }
        ClassReader reader = new ClassReader(original);
        // ProGuard inlines BlockBreakHelper.tick into InputOverrideHandler.onTick.
        // In the two mining branches local 1 is the baritone.fd helper, with its
        // original IPlayerContext field. The third swing belongs to right-click.
        int[] swings = {0}, animations = {0}, methods = {0};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("onTick") || !desc.equals("(Lbaritone/api/event/events/TickEvent;)V")) return next;
                methods[0]++;
                return new MethodVisitor(ASM9, next) {
                    private void player() {
                        super.visitVarInsn(ALOAD, 1);
                        super.visitFieldInsn(GETFIELD, "baritone/fd", "a", "L" + CTX + ";");
                        super.visitMethodInsn(INVOKEINTERFACE, CTX, "player", "()L" + PLAYER + ";", true);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                        if (owner.equals(PUNCH)) throw new IllegalStateException("Upstream already contains Punch");
                        if (opcode == GETSTATIC && owner.equals(ANIMATION) && field.equals("DEFAULT") && animations[0] < 2) {
                            animations[0]++; player();
                            super.visitFieldInsn(GETSTATIC, HAND, "MAIN_HAND", "L" + HAND + ";");
                            super.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "getItemInHand", "(L" + HAND + ";)Lnet/minecraft/world/item/ItemStack;", false);
                            super.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "getAttackAnimation", "()L" + ANIMATION + ";", false);
                        } else super.visitFieldInsn(opcode, owner, field, descriptor);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                        if (owner.equals(PLAYER) && method.equals("swing") && descriptor.equals("(L" + HAND + ";L" + ANIMATION + ";Z)Z")) {
                            swings[0]++;
                            if (swings[0] > 2) return;
                            player();
                            super.visitFieldInsn(GETFIELD, PLAYER, "connection", "Lnet/minecraft/client/multiplayer/ClientPacketListener;");
                            super.visitFieldInsn(GETSTATIC, PUNCH, "INSTANCE", "L" + PUNCH + ";");
                            super.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/multiplayer/ClientPacketListener", "send", "(Lnet/minecraft/network/protocol/Packet;)V", false);
                        }
                    }
                };
            }
        }, 0);
        if (methods[0] != 1 || swings[0] != 3 || animations[0] != 2) throw new IllegalStateException("Unexpected mining swing structure");
        Path output = Path.of(args[1], OWNER + ".class"); Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
        System.out.println("Mining patched: two held-item animations and two normal Punch sends");
    }
}
