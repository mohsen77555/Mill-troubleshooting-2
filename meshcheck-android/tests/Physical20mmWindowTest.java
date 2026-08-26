import com.alhadi.meshcheck.ThreadCountConsensus;
import com.alhadi.meshcheck.ThreadProfileCounter;
import java.util.Random;

public class Physical20mmWindowTest {
    private static float[] synthetic(int n, double threadsPerCm, double physicalCm, double phase, double noise) {
        float[] p=new float[n];
        Random r=new Random((long)(threadsPerCm*1000+phase*10000));
        double totalThreads=threadsPerCm*physicalCm;
        double pitch=(n-1)/totalThreads;
        for(int x=0;x<n;x++){
            double q=((x-phase*pitch+pitch/2)%pitch+pitch)%pitch-pitch/2;
            double sigma=pitch*0.28/2.355;
            double band=Math.exp(-0.5*q*q/(sigma*sigma));
            p[x]=(float)(215-155*band+r.nextGaussian()*noise);
        }
        return p;
    }
    private static ThreadCountConsensus.FrameResult frame(double density){
        ThreadProfileCounter.Result[] scans=new ThreadProfileCounter.Result[9];
        for(int i=0;i<9;i++) scans[i]=ThreadProfileCounter.analyze(
                synthetic(1000,density,2.0,0.13+i*0.01,4.5),2.0f);
        return ThreadCountConsensus.fuse(scans,2.0f);
    }
    private static void near(double e,double a,double t,String name){
        if(Math.abs(e-a)>t)throw new AssertionError(name+" expected "+e+" got "+a);
    }
    public static void main(String[] args){
        ThreadCountConsensus.FrameResult a=frame(4.3);
        if(!a.ok)throw new AssertionError(a.reason);
        near(4.3,a.threadsPerCm,0.18,"4.3/cm in 20mm");
        if(a.currentFullLineCount<7||a.currentFullLineCount>10)throw new AssertionError("expected about 8-9 full lines in 20mm");
        ThreadCountConsensus.FrameResult b=frame(5.9);
        if(!b.ok)throw new AssertionError(b.reason);
        near(5.9,b.threadsPerCm,0.18,"5.9/cm in 20mm");
        if(b.currentFullLineCount<10||b.currentFullLineCount>13)throw new AssertionError("expected about 11-12 full lines in 20mm");
        System.out.println("physical 20mm thread density tests passed");
    }
}
