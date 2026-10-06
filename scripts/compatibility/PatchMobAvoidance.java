/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
/** Pinned release overlay matching Avoidance.create's hostile-only filter in source. */
public final class PatchMobAvoidance implements Opcodes {
    private static final String OWNER="baritone/fz", ENTITY="net/minecraft/world/entity/Entity";
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[1]); int[] changes=new int[3];
        try (ZipFile z=new ZipFile(args[0])) {
            ClassReader r=new ClassReader(z.getInputStream(z.getEntry(OWNER+".class")).readAllBytes());
            ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
            r.accept(new ClassVisitor(ASM9,w) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    MethodVisitor next=super.visitMethod(access,name,desc,signature,exceptions);
                    // Avoidance.create(ctx): add remembered hunters before each return.
                    if (name.equals("a") && desc.equals("(Lbaritone/api/utils/IPlayerContext;)Ljava/util/List;") && (access&ACC_STATIC)!=0) return new MethodVisitor(ASM9,next) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode==ARETURN) {
                                changes[2]++;
                                // Only the returned list is used: the stack map already drops the context local here.
                                super.visitInsn(DUP);super.visitLdcInsn(Type.getObjectType(OWNER));
                                super.visitMethodInsn(INVOKESTATIC,"baritone/utils/MobSafety","augment","(Ljava/util/List;Ljava/lang/Class;)V",false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                    boolean radius=name.equals("a") && desc.equals("(Ljava/util/List;DL"+ENTITY+";)V") && (access&ACC_STATIC)!=0;
                    // Synthetic avoidance lambda: list.add(new Avoidance(entity.blockPosition(), coefficient, mobAvoidanceRadius))
                    if (radius) return new MethodVisitor(ASM9,next) {
                        @Override public void visitMethodInsn(int opcode,String owner,String called,String calledDesc,boolean itf) {
                            super.visitMethodInsn(opcode,owner,called,calledDesc,itf);
                            if (opcode==INVOKEVIRTUAL && owner.equals("java/lang/Integer") && called.equals("intValue")) {
                                changes[1]++;
                                super.visitVarInsn(ALOAD,3);super.visitInsn(SWAP);
                                super.visitMethodInsn(INVOKESTATIC,"baritone/utils/MobSafety","avoidRadius","(L"+ENTITY+";I)I",false);
                            }
                        }
                    };
                    // Synthetic filter lambda: entity -> entity instanceof Mob
                    if (!(name.equals("c") && desc.equals("(L"+ENTITY+";)Z") && (access&ACC_STATIC)!=0)) return next;
                    return new MethodVisitor(ASM9,next) {
                        @Override public void visitTypeInsn(int opcode,String type) {
                            if (opcode==INSTANCEOF && type.equals("net/minecraft/world/entity/Mob")) {
                                changes[0]++;
                                super.visitMethodInsn(INVOKESTATIC,"baritone/utils/MobSafety","hostile","(L"+ENTITY+";)Z",false);
                            } else super.visitTypeInsn(opcode,type);
                        }
                    };
                }
            },0);
            Path p=out.resolve(OWNER+".class");Files.createDirectories(p.getParent());Files.write(p,w.toByteArray());
        }
        if (changes[0]!=1 || changes[1]!=1 || changes[2]!=2) throw new IllegalStateException("Unexpected pinned avoidance structure");
        System.out.println("Mob avoidance: hostile-only path filter (passive animals and villagers no longer avoided), per-type radius, remembered hunters");
    }
}
