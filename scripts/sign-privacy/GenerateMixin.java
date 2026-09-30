/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;

/** Offline equivalent of compiling MixinSignEditScreen.java, plus a target-bytecode check. */
public final class GenerateMixin implements Opcodes {
    static final String TARGET = "net/minecraft/client/gui/screens/inventory/AbstractSignEditScreen";
    static final String CTOR = "(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V";

    private static void requireMethod(ZipFile jar, String owner, String name, String descriptor) throws Exception {
        boolean[] found = {false};
        new ClassReader(jar.getInputStream(jar.getEntry(owner + ".class"))).accept(new ClassVisitor(ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                if ((access & ACC_PUBLIC) != 0 && method.equals(name) && desc.equals(descriptor)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!found[0]) throw new IllegalStateException("Missing public Minecraft API: " + owner + "." + name + descriptor);
    }

    public static void main(String[] args) throws Exception {
        int[] matches = {0};
        try (ZipFile jar = new ZipFile(args[0])) {
            String component = "net/minecraft/network/chat/Component";
            String translation = "net/minecraft/network/chat/contents/TranslatableContents";
            requireMethod(jar, component, "getContents", "()Lnet/minecraft/network/chat/ComponentContents;");
            requireMethod(jar, component, "getSiblings", "()Ljava/util/List;");
            requireMethod(jar, component, "plainCopy", "()Lnet/minecraft/network/chat/MutableComponent;");
            requireMethod(jar, component, "getString", "()Ljava/lang/String;");
            requireMethod(jar, translation, "getKey", "()Ljava/lang/String;");
            requireMethod(jar, translation, "getFallback", "()Ljava/lang/String;");
            requireMethod(jar, translation, "getArgs", "()[Ljava/lang/Object;");
            requireMethod(jar, translation, "<init>", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V");
            requireMethod(jar, "net/minecraft/network/chat/MutableComponent", "create",
                    "(Lnet/minecraft/network/chat/ComponentContents;)Lnet/minecraft/network/chat/MutableComponent;");
            new ClassReader(jar.getInputStream(jar.getEntry(TARGET + ".class"))).accept(new ClassVisitor(ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!name.equals("<init>") || !desc.equals(CTOR)) return null;
                    return new MethodVisitor(ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (opcode == INVOKEINTERFACE && owner.equals("net/minecraft/network/chat/Component")
                                    && name.equals("getString") && desc.equals("()Ljava/lang/String;")) matches[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        if (matches[0] != 1) throw new IllegalStateException("Expected one sign conversion; found " + matches[0]);

        ClassWriter writer = new ClassWriter(0);
        String name = "baritone/launch/mixins/MixinSignEditScreen";
        writer.visit(V25, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitSource("MixinSignEditScreen.java", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TARGET));
        targets.visitEnd(); mixin.visitEnd();

        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd();

        MethodVisitor redirect = writer.visitMethod(ACC_PRIVATE, "baritone$privateSignText",
                "(Lnet/minecraft/network/chat/Component;)Ljava/lang/String;", null, null);
        AnnotationVisitor annotation = redirect.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Redirect;", true);
        AnnotationVisitor methods = annotation.visitArray("method");
        methods.visit(null, "<init>" + CTOR); methods.visitEnd();
        AnnotationVisitor at = annotation.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;");
        at.visitEnd(); annotation.visit("require", 1); annotation.visit("allow", 1); annotation.visitEnd();
        redirect.visitCode(); redirect.visitVarInsn(ALOAD, 1);
        redirect.visitMethodInsn(INVOKESTATIC, "baritone/launch/privacy/SignTextPrivacy", "getString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        redirect.visitInsn(ARETURN); redirect.visitMaxs(1, 2); redirect.visitEnd(); writer.visitEnd();
        Path output = Path.of(args[1], name + ".class");
        Files.createDirectories(output.getParent()); Files.write(output, writer.toByteArray());
    }
}
