import com.alhadi.meshcheck.Marker20mmDetector;

public class Marker20mmDetectorTest {
    private static void fill(byte[] g, int w, int x0, int y0, int x1, int y1, int value) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) g[y*w+x] = (byte)value;
    }
    private static void near(float expected, float actual, float tolerance, String name) {
        if (Math.abs(expected-actual)>tolerance) throw new AssertionError(name+" expected "+expected+" got "+actual);
    }
    public static void main(String[] args) {
        int w=400,h=400;
        byte[] gray=new byte[w*h];
        for(int i=0;i<gray.length;i++) gray[i]=(byte)235;
        // Printable geometry: four isolated 5x5 mm blocks. Their inward corners
        // define a 20x20 mm square from about (120,120) to (280,280).
        fill(gray,w,80,80,120,120,15);
        fill(gray,w,280,80,320,120,15);
        fill(gray,w,80,280,120,320,15);
        fill(gray,w,280,280,320,320,15);
        Marker20mmDetector.Result r=Marker20mmDetector.detect(gray,w,h);
        if(!r.ok) throw new AssertionError(r.reason);
        if(r.corners.length!=4) throw new AssertionError("four inner corners required");
        near(119,r.corners[0].x,3,"TL x"); near(119,r.corners[0].y,3,"TL y");
        near(280,r.corners[1].x,3,"TR x"); near(119,r.corners[1].y,3,"TR y");
        near(280,r.corners[2].x,3,"BR x"); near(280,r.corners[2].y,3,"BR y");
        near(119,r.corners[3].x,3,"BL x"); near(280,r.corners[3].y,3,"BL y");
        if(r.confidence<0.45f) throw new AssertionError("marker confidence too low: "+r.confidence);
        System.out.println("20x20 marker detector test passed");
    }
}
