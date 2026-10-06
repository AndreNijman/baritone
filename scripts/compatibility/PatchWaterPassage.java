/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
/** Pinned release overlay matching MovementHelper.canWalkThroughPosition's WaterPassage check in source. */
public final class PatchWaterPassage implements Opcodes {
    private static final String OWNER="baritone/cc", MOVEMENT="baritone/cb", BSI="baritone/ff", STATE="net/minecraft/world/level/block/state/BlockState";
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[1]); int[] changes=new int[4];
        try (ZipFile z=new ZipFile(args[0])) {
          for (String target : new String[]{OWNER,MOVEMENT}) {
            ClassReader r=new ClassReader(z.getInputStream(z.getEntry(target+".class")).readAllBytes());
            ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
            r.accept(new ClassVisitor(ASM9,w) {
                private String owner() { return target; }
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                    MethodVisitor next=super.visitMethod(access,name,desc,signature,exceptions);
                    if (owner().equals(MOVEMENT)) {
                        // Movement.update(): ... position().y < dest.y + 0.6 -> WaterPassage.afloat(ctx, dest)
                        if (!(name.equals("update") && desc.equals("()Lbaritone/api/pathing/movement/MovementStatus;"))) return next;
                        return new MethodVisitor(ASM9,next) {
                            @Override public void visitLdcInsn(Object value) {
                                if (value instanceof Double d && d==0.6) {
                                    changes[1]++;
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"a","Lbaritone/api/utils/IPlayerContext;");
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"b","Lbaritone/api/utils/BetterBlockPos;");
                                    super.visitMethodInsn(INVOKESTATIC,"baritone/utils/WaterPassage","afloat","(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/utils/BetterBlockPos;)D",false);
                                } else super.visitLdcInsn(value);
                            }
                            @Override public void visitInsn(int opcode) {
                                if (opcode==ARETURN) {
                                    changes[2]++;
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"a","Lbaritone/api/utils/IPlayerContext;");
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"b","Lbaritone/api/utils/BetterBlockPos;");
                                    super.visitMethodInsn(INVOKESTATIC,"baritone/utils/WaterPassage","settle","(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/utils/BetterBlockPos;)V",false);
                                    // GradualLook.alignJump(ctx, src, dest)
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"a","Lbaritone/api/utils/IPlayerContext;");
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"a","Lbaritone/api/utils/BetterBlockPos;");
                                    super.visitVarInsn(ALOAD,0);super.visitFieldInsn(GETFIELD,MOVEMENT,"b","Lbaritone/api/utils/BetterBlockPos;");
                                    super.visitMethodInsn(INVOKESTATIC,"baritone/utils/GradualLook","alignJump","(Lbaritone/api/utils/IPlayerContext;Lbaritone/api/utils/BetterBlockPos;Lbaritone/api/utils/BetterBlockPos;)V",false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    // canWalkThroughBlockState(state): fluid amount != 8 -> NO
                    if (name.equals("a") && desc.equals("(L"+STATE+";)Lbaritone/dq;") && (access&ACC_STATIC)!=0) return new MethodVisitor(ASM9,next) {
                        @Override public void visitMethodInsn(int opcode,String owner,String called,String calledDesc,boolean itf) {
                            super.visitMethodInsn(opcode,owner,called,calledDesc,itf);
                            if (opcode==INVOKEVIRTUAL && owner.equals("net/minecraft/world/level/material/Fluid") && called.equals("getAmount")) {
                                changes[3]++;
                                super.visitVarInsn(ALOAD,0);
                                super.visitMethodInsn(INVOKESTATIC,"baritone/utils/WaterPassage","stateAmount","(IL"+STATE+";)I",false);
                            }
                        }
                    };
                    // canWalkThroughPosition(bsi, x, y, z, state)
                    if (!(name.equals("c") && desc.equals("(L"+BSI+";IIIL"+STATE+";)Z") && (access&ACC_STATIC)!=0)) return next;
                    return new MethodVisitor(ASM9,next) {
                        @Override public void visitMethodInsn(int opcode,String owner,String called,String calledDesc,boolean itf) {
                            super.visitMethodInsn(opcode,owner,called,calledDesc,itf);
                            // isFlowing(x, y, z, state, bsi) -> WaterPassage.blocks(flowing, state, bsi.get0(x,y+1,z), bsi.get0(x,y-1,z))
                            if (opcode==INVOKESTATIC && owner.equals(OWNER) && called.equals("a") && calledDesc.equals("(IIIL"+STATE+";L"+BSI+";)Z")) {
                                changes[0]++;
                                super.visitVarInsn(ALOAD,4);
                                for (int offset : new int[]{1,-1}) {
                                    super.visitVarInsn(ALOAD,0);super.visitVarInsn(ILOAD,1);super.visitVarInsn(ILOAD,2);
                                    super.visitInsn(ICONST_1);super.visitInsn(offset>0 ? IADD : ISUB);super.visitVarInsn(ILOAD,3);
                                    super.visitMethodInsn(INVOKEVIRTUAL,BSI,"a","(III)L"+STATE+";",false);
                                }
                                super.visitMethodInsn(INVOKESTATIC,"baritone/utils/WaterPassage","blocks","(ZL"+STATE+";L"+STATE+";L"+STATE+";)Z",false);
                            }
                        }
                    };
                }
            },0);
            Path p=out.resolve(target+".class");Files.createDirectories(p.getParent());Files.write(p,w.toByteArray());
          }
        }
        if (changes[0]!=1 || changes[1]!=1 || changes[2]!=1 || changes[3]!=1) throw new IllegalStateException("Unexpected pinned water passability structure");
        System.out.println("Water passage: shallow horizontally flowing water is walkable; low-ceiling wet entries float only to the floor and sink before entering");
    }
}
