/* SPDX-License-Identifier: LGPL-3.0-or-later */
import baritone.api.utils.Rotation;
import baritone.utils.GradualLook;
import java.util.Random;
/** Geometry checks for wrapped turns, sensitivity quantization and convergence. */
public final class GradualLookTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("check "+assertions); }
    public static void main(String[] args) {
        Random rng = new Random(1263);
        for (int i=0;i<10000;i++) {
            double sensitivity=rng.nextDouble();
            Rotation previous=new Rotation(rng.nextFloat()*1440-720,rng.nextFloat()*180-90);
            Rotation target=new Rotation(rng.nextFloat()*1440-720,rng.nextFloat()*180-90);
            Rotation next=GradualLook.step(previous,target,sensitivity);
            float yaw=Rotation.normalizeYaw(next.getYaw()-previous.getYaw()),pitch=next.getPitch()-previous.getPitch();
            check(Math.abs(yaw)<=12.0001);check(Math.abs(pitch)<=8.0001);check(next.getPitch()>=-90 && next.getPitch()<=90);
            double f=sensitivity*(double)0.6f+(double)0.2f;float q=(float)(f*f*f*8d)*0.15f;
            check(Math.abs(yaw/q-Math.round(yaw/q))<0.02);check(Math.abs(pitch/q-Math.round(pitch/q))<0.02);
            if (i<100) {
                Rotation current=previous;
                for(int tick=0;tick<128;tick++) current=GradualLook.step(current,target,sensitivity);
                check(Math.abs(Rotation.normalizeYaw(current.getYaw()-target.getYaw()))<=q+0.001);
                check(Math.abs(current.getPitch()-target.getPitch())<=q+0.001);
            }
        }
        Rotation wrap=GradualLook.step(new Rotation(179,0),new Rotation(-179,0),0.5);
        check(wrap.getYaw()>179 && wrap.getYaw()<181);
        Rotation target=new Rotation(90,20);GradualLook.enable(false);check(GradualLook.limit(target,new Rotation(0,0),null)==target);
        System.out.println("Gradual look geometry: "+assertions+" assertions passed");
    }
}
