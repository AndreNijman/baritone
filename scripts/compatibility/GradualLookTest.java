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
        for (int mask=0;mask<128;mask++) for (int degrees=-180;degrees<=180;degrees++) {
            int selected=GradualLook.steeringKeys(mask,degrees,0);
            check((selected&~15)==(mask&~15));
            int f=((mask&1)!=0?1:0)-((mask&2)!=0?1:0), l=((mask&4)!=0?1:0)-((mask&8)!=0?1:0);
            if(f==0 && l==0) { check(selected==mask);continue; }
            int sf=((selected&1)!=0?1:0)-((selected&2)!=0?1:0), sl=((selected&4)!=0?1:0)-((selected&8)!=0?1:0);
            float wanted=(float)(degrees-Math.toDegrees(Math.atan2(l,f))), actual=(float)-Math.toDegrees(Math.atan2(sl,sf));
            check(Math.abs(Rotation.normalizeYaw(wanted-actual))<=22.501);
        }
        boolean[] eligible={false,true,true,true,false,false,true,true};
        for(int state=0;state<8;state++) check(GradualLook.canSteer((state&1)!=0,(state&2)!=0,(state&4)!=0)==eligible[state]);
        GradualLook.steerInput(null);
        Rotation wrap=GradualLook.step(new Rotation(179,0),new Rotation(-179,0),0.5);
        check(wrap.getYaw()>179 && wrap.getYaw()<181);
        Rotation target=new Rotation(90,20);GradualLook.enable(false);check(GradualLook.limit(target,new Rotation(0,0),null)==target);
        System.out.println("Gradual look geometry: "+assertions+" assertions passed");
    }
}
