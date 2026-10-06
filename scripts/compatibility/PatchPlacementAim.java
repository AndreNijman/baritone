/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.*;
/**
 * Pinned release overlay: block-placement feasibility (MovementHelper.attemptToPlaceABlock, BuilderProcess) raytraces
 * along the aim's eventual endpoint, matching the source. The bounded next-tick step never reaches a face that is
 * more than one step away, so the look target was never set and placement waited for the player to aim manually.
 */
public final class PatchPlacementAim implements Opcodes {
    private static final String AIM="baritone/api/behavior/look/IAimProcessor", ROT="(Lbaritone/api/utils/Rotation;)Lbaritone/api/utils/Rotation;";
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[1]); int[] changes=new int[2];
        try (ZipFile z=new ZipFile(args[0])) {
            String[] owners={"baritone/cc","baritone/dt"};
            for (int i=0;i<owners.length;i++) {
                String owner=owners[i]; int index=i;
                // MovementHelper was already rewritten by PatchWaterPassage.
                byte[] bytes=owner.equals("baritone/cc") ? Files.readAllBytes(out.resolve(owner+".class")) : z.getInputStream(z.getEntry(owner+".class")).readAllBytes();
                ClassReader r=new ClassReader(bytes); ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
                r.accept(new ClassVisitor(ASM9,w) {
                    @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                        return new MethodVisitor(ASM9,super.visitMethod(access,name,desc,signature,exceptions)) {
                            @Override public void visitMethodInsn(int opcode,String calledOwner,String called,String calledDesc,boolean itf) {
                                if (opcode==INVOKEINTERFACE && calledOwner.equals(AIM) && called.equals("peekRotation") && calledDesc.equals(ROT)) {
                                    changes[index]++;
                                    super.visitMethodInsn(opcode,calledOwner,"peekRotationForReachability",calledDesc,itf);
                                } else super.visitMethodInsn(opcode,calledOwner,called,calledDesc,itf);
                            }
                        };
                    }
                },0);
                Path p=out.resolve(owner+".class");Files.createDirectories(p.getParent());Files.write(p,w.toByteArray());
            }
        }
        if (changes[0]!=1 || changes[1]!=1) throw new IllegalStateException("Unexpected pinned placement structure");
        System.out.println("Placement aim: movement and builder placement checks use the eventual aim, not the bounded step");
    }
}
