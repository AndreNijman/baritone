/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
/** Pinned release overlay matching the GradualLook source integration. */
public final class PatchGradualLook implements Opcodes {
    private static final String CTX="baritone/api/utils/IPlayerContext", ROT="baritone/api/utils/Rotation", HOOK="baritone/utils/GradualLook";
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[1]); int[] changes=new int[10];
        try (ZipFile z=new ZipFile(args[0])) {
            for (String owner : new String[]{"baritone/a","baritone/f","baritone/f$a","baritone/fj","baritone/api/utils/RotationUtils","baritone/f$b","baritone/api/behavior/look/IAimProcessor"}) {
                byte[] bytes=owner.equals("baritone/fj") ? Files.readAllBytes(out.resolve(owner+".class")) : z.getInputStream(z.getEntry(owner+".class")).readAllBytes();
                ClassReader r=new ClassReader(bytes); ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
                r.accept(new ClassVisitor(ASM9,w) {
                    @Override public void visitEnd() {
                        if (owner.equals("baritone/api/behavior/look/IAimProcessor") || owner.equals("baritone/f$a")) {
                            boolean base=owner.equals("baritone/api/behavior/look/IAimProcessor");
                            changes[base ? 8 : 9]++;
                            MethodVisitor m=super.visitMethod(ACC_PUBLIC,"peekRotationForReachability","(L"+ROT+";)L"+ROT+";",null,null);
                            m.visitCode();m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);
                            if (base) m.visitMethodInsn(INVOKEINTERFACE,owner,"peekRotation","(L"+ROT+";)L"+ROT+";",true);
                            else {
                                m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,owner,"a","L"+CTX+";");
                                m.visitMethodInsn(INVOKESTATIC,HOOK,"reachableRotation","(Lbaritone/api/behavior/look/IAimProcessor;L"+ROT+";L"+CTX+";)L"+ROT+";",false);
                            }
                            m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();
                        }
                        super.visitEnd();
                    }
                    @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        MethodVisitor next=super.visitMethod(access,name,desc,signature,exceptions);
                        boolean ctor=owner.equals("baritone/a") && name.equals("<init>") && desc.equals("(Lnet/minecraft/client/Minecraft;)V");
                        boolean request=owner.equals("baritone/f") && name.equals("updateTarget") && desc.equals("(L"+ROT+";Z)V");
                        boolean aim=owner.equals("baritone/f$a") && name.equals("peekRotation") && desc.equals("(L"+ROT+";)L"+ROT+";");
                        boolean mining=owner.equals("baritone/fj") && name.equals("onTick") && desc.equals("(Lbaritone/api/event/events/TickEvent;)V");
                        boolean reach=owner.equals("baritone/api/utils/RotationUtils") && name.equals("reachableOffset") && desc.equals("(L"+CTX+";Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;DZ)Ljava/util/Optional;");
                        boolean begin=owner.equals("baritone/f") && name.equals("onTick") && desc.equals("(Lbaritone/api/event/events/TickEvent;)V");
                        boolean previous=owner.equals("baritone/f$b") && name.equals("a") && desc.equals("()L"+ROT+";");
                        boolean clear=owner.equals("baritone/f") && name.equals("onWorldEvent") && desc.equals("(Lbaritone/api/event/events/WorldEvent;)V");
                        if (!(ctor||request||aim||mining||reach||begin||previous||clear)) return next;
                        return new MethodVisitor(ASM9,next) {
                            private boolean mineDone;
                            @Override public void visitCode() {
                                super.visitCode();
                                if (clear) {
                                    changes[7]++;super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"baritone/c","a","L"+CTX+";");
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"clear","(L"+CTX+";)V",false);
                                }
                                if (request) {
                                    changes[1]++;
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"baritone/c","a","L"+CTX+";");
                                    super.visitVarInsn(ALOAD,1);super.visitVarInsn(ILOAD,2);
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"request","(L"+CTX+";L"+ROT+";Z)V",false);
                                }
                            }
                            @Override public void visitMethodInsn(int opcode,String calledOwner,String calledName,String calledDesc,boolean itf) {
                                if (begin && calledOwner.equals("baritone/f$a") && calledName.equals("tick") && calledDesc.equals("()V")) {
                                    changes[5]++;super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"baritone/c","a","L"+CTX+";");
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"beginTick","(L"+CTX+";)V",false);
                                }
                                if (reach && calledOwner.equals("baritone/api/behavior/look/IAimProcessor") && calledName.equals("peekRotation")) {
                                    changes[4]++;
                                    super.visitMethodInsn(INVOKEINTERFACE,calledOwner,"peekRotationForReachability",calledDesc,true);
                                } else super.visitMethodInsn(opcode,calledOwner,calledName,calledDesc,itf);
                            }
                            @Override public void visitInsn(int opcode) {
                                if (ctor && opcode==RETURN) {
                                    changes[0]++;super.visitVarInsn(ALOAD,0);
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"register","(Lbaritone/api/IBaritone;)V",false);
                                }
                                if (previous && opcode==ARETURN) {
                                    changes[6]++;super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,"baritone/f$a","a","L"+CTX+";");
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"previous","(L"+ROT+";L"+CTX+";)L"+ROT+";",false);
                                }
                                if (aim && opcode==ARETURN) {
                                    changes[2]++;super.visitVarInsn(ALOAD,2);super.visitVarInsn(ALOAD,0);
                                    super.visitFieldInsn(GETFIELD,owner,"a","L"+CTX+";");
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"limit","(L"+ROT+";L"+ROT+";L"+CTX+";)L"+ROT+";",false);
                                }
                                super.visitInsn(opcode);
                            }
                            @Override public void visitVarInsn(int opcode,int variable) {
                                super.visitVarInsn(opcode,variable);
                                if (mining && !mineDone && opcode==ILOAD && variable==2) {
                                    mineDone=true;changes[3]++;
                                    super.visitVarInsn(ALOAD,1);super.visitFieldInsn(GETFIELD,"baritone/fd","a","L"+CTX+";");
                                    super.visitVarInsn(ALOAD,1);super.visitFieldInsn(GETFIELD,"baritone/fd","a","Z");
                                    super.visitMethodInsn(INVOKESTATIC,HOOK,"allowMining","(ZL"+CTX+";Z)Z",false);
                                }
                            }
                        };
                    }
                },0);
                Path p=out.resolve(owner+".class");Files.createDirectories(p.getParent());Files.write(p,w.toByteArray());
            }
        }
        for (int n:changes) if(n!=1) throw new IllegalStateException("Unexpected pinned gradual-look structure");
        System.out.println("Gradual look: registered command, pure live/fork aim limit, interaction target and mining view gate");
    }
}
