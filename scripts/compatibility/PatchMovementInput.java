/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Apply the source-level keyboard-input fix to the pinned, optimized API release. */
public final class PatchMovementInput implements Opcodes {
    private static final String INPUT = "baritone/api/utils/input/Input";
    public static void main(String[] args) throws Exception {
        byte[] original = null;
        try (ZipFile jar = new ZipFile(args[0])) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if ("net/minecraft/client/player/ClientInput".equals(new ClassReader(bytes).getSuperName())) {
                    if (original != null) throw new IllegalStateException("Ambiguous movement input class");
                    original = bytes;
                }
            }
        }
        if (original == null) throw new IllegalStateException("Movement input class missing");
        ClassReader reader = new ClassReader(original);
        String owner = reader.getClassName();
        String[] handler = new String[2];
        Set<String> keys = new HashSet<>();
        int[] calls = {0};
        reader.accept(new ClassVisitor(ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                if (desc.startsWith("Lbaritone/")) {
                    if (handler[0] != null) throw new IllegalStateException("Ambiguous handler field");
                    handler[0] = name; handler[1] = desc.substring(1, desc.length() - 1);
                }
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals("tick") || !desc.equals("()V")) return null;
                return new MethodVisitor(ASM9) {
                    @Override public void visitFieldInsn(int opcode, String fieldOwner, String name, String desc) {
                        if (opcode == GETSTATIC && fieldOwner.equals(INPUT)) keys.add(name);
                    }
                    @Override public void visitMethodInsn(int opcode, String methodOwner, String name, String desc, boolean itf) {
                        if (name.equals("isInputForcedDown") && desc.equals("(L" + INPUT + ";)Z")) calls[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String[] ordered = {"MOVE_FORWARD", "MOVE_BACK", "MOVE_LEFT", "MOVE_RIGHT", "JUMP", "SNEAK", "SPRINT"};
        if (handler[0] == null || calls[0] != 7 || !keys.equals(Set.of(ordered)))
            throw new IllegalStateException("Unexpected pinned movement input structure");
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (name.equals("tick") && desc.equals("()V")) return null;
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
            @Override public void visitEnd() {
                MethodVisitor m = super.visitMethod(ACC_PUBLIC, "tick", "()V", null, null);
                m.visitCode();
                for (int i = 0; i < ordered.length; i++) {
                    m.visitVarInsn(ALOAD, 0);
                    m.visitFieldInsn(GETFIELD, owner, handler[0], "L" + handler[1] + ";");
                    m.visitFieldInsn(GETSTATIC, INPUT, ordered[i], "L" + INPUT + ";");
                    m.visitMethodInsn(INVOKEVIRTUAL, handler[1], "isInputForcedDown", "(L" + INPUT + ";)Z", false);
                    m.visitVarInsn(ISTORE, i + 1);
                }
                m.visitVarInsn(ALOAD, 0);
                m.visitTypeInsn(NEW, "net/minecraft/world/phys/Vec2"); m.visitInsn(DUP);
                m.visitVarInsn(ILOAD, 3); m.visitVarInsn(ILOAD, 4); m.visitInsn(ISUB); m.visitInsn(I2F);
                m.visitVarInsn(ILOAD, 1); m.visitVarInsn(ILOAD, 2); m.visitInsn(ISUB); m.visitInsn(I2F);
                m.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/phys/Vec2", "<init>", "(FF)V", false);
                m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/phys/Vec2", "normalized", "()Lnet/minecraft/world/phys/Vec2;", false);
                m.visitFieldInsn(PUTFIELD, owner, "moveVector", "Lnet/minecraft/world/phys/Vec2;");
                m.visitVarInsn(ALOAD, 0); m.visitTypeInsn(NEW, "net/minecraft/world/entity/player/Input"); m.visitInsn(DUP);
                for (int i = 1; i <= 7; i++) m.visitVarInsn(ILOAD, i);
                m.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/entity/player/Input", "<init>", "(ZZZZZZZ)V", false);
                m.visitFieldInsn(PUTFIELD, owner, "keyPresses", "Lnet/minecraft/world/entity/player/Input;");
                m.visitInsn(RETURN); m.visitMaxs(10, 8); m.visitEnd(); super.visitEnd();
            }
        }, 0);
        Path path = Path.of(args[1], owner + ".class");
        Files.createDirectories(path.getParent()); Files.write(path, writer.toByteArray());
        System.out.println("Movement input patched: " + owner);
    }
}
