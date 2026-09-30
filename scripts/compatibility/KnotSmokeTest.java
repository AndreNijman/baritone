/* SPDX-License-Identifier: LGPL-3.0-or-later */
import java.nio.file.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;
import org.objectweb.asm.*;

/** Loads the unchanged candidate through cached Fabric; does not start a game or connect. */
public final class KnotSmokeTest {
    public static void main(String[] args) throws Exception {
        Knot knot = new Knot(EnvType.CLIENT);
        ClassLoader target = knot.init(new String[]{"--gameDir", "/scratch/game", "--version", "26.3"});
        String name = "net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen";
        Class<?> screen = target.loadClass(name);
        boolean control = args.length > 0 && args[0].equals("--upstream-control");
        if (control) {
            for (var method : screen.getDeclaredMethods()) {
                if (method.getName().contains("privateSignText")) throw new AssertionError("Control contains patched hook");
            }
            knot.addToClassPath(Path.of("/scratch/game-tests"));
            target.loadClass("SignRoundTripTest").getMethod("main", String[].class).invoke(null, (Object) new String[]{"--upstream-control"});
            return;
        }
        byte[] transformed = Files.readAllBytes(Path.of(".mixin.out/class/" + name.replace('.', '/') + ".class"));
        int[] calls = new int[2];
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                if (!method.equals("<init>")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (name.contains("privateSignText")) calls[0]++;
                        if (owner.equals("net/minecraft/network/chat/Component") && name.equals("getString")) calls[1]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (calls[0] != 1 || calls[1] != 0) throw new AssertionError("Redirect calls=" + calls[0] + ", unredirected conversions=" + calls[1]);
        System.out.println("Fabric/Mixin runtime: actual sign constructor has exactly one redirect and no original conversion");
        System.out.println("Test-only loader dependency override: cached Fabric 0.19.3; installation still requires 0.19.5+");
        knot.addToClassPath(Path.of("/scratch/game-tests"));
        target.loadClass("SignRoundTripTest").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
}
