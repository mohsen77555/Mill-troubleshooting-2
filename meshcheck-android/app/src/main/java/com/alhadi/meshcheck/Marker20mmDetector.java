package com.alhadi.meshcheck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Detects the four black 5x5 mm corner blocks of the MeshCheck printable frame.
 * Their four inward-facing corners define the true 20x20 mm measurement square.
 */
public final class Marker20mmDetector {
    private Marker20mmDetector() {}

    public static final class Point {
        public final float x, y;
        Point(float x, float y) { this.x = x; this.y = y; }
    }

    public static final class Result {
        public final boolean ok;
        public final String reason;
        /** Inward marker corners in TL, TR, BR, BL order. */
        public final Point[] corners;
        public final float confidence;

        private Result(boolean ok, String reason, Point[] corners, float confidence) {
            this.ok = ok;
            this.reason = reason;
            this.corners = corners == null ? new Point[0] : corners;
            this.confidence = confidence;
        }

        public static Result fail(String reason) { return new Result(false, reason, null, 0f); }
    }

    private static final class Component {
        int area;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        long sumX, sumY;
        int maxSum = Integer.MIN_VALUE, maxSumX, maxSumY;
        int minSum = Integer.MAX_VALUE, minSumX, minSumY;
        int maxXMinusY = Integer.MIN_VALUE, maxXMinusYX, maxXMinusYY;
        int maxYMinusX = Integer.MIN_VALUE, maxYMinusXX, maxYMinusXY;

        void add(int x, int y) {
            area++;
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            sumX += x; sumY += y;
            int s = x + y;
            if (s > maxSum) { maxSum = s; maxSumX = x; maxSumY = y; }
            if (s < minSum) { minSum = s; minSumX = x; minSumY = y; }
            int d1 = x - y;
            if (d1 > maxXMinusY) { maxXMinusY = d1; maxXMinusYX = x; maxXMinusYY = y; }
            int d2 = y - x;
            if (d2 > maxYMinusX) { maxYMinusX = d2; maxYMinusXX = x; maxYMinusXY = y; }
        }

        float cx() { return sumX / (float) Math.max(1, area); }
        float cy() { return sumY / (float) Math.max(1, area); }
        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        float fill() { return area / (float) Math.max(1, width() * height()); }
    }

    /** Detect on a contiguous 8-bit grayscale image. */
    public static Result detect(byte[] gray, int width, int height) {
        if (gray == null || width < 120 || height < 120 || gray.length < width * height) {
            return Result.fail("صورة marker صغيرة.");
        }

        int[] histogram = new int[256];
        long sum = 0;
        for (int i = 0; i < width * height; i++) {
            int v = gray[i] & 0xFF;
            histogram[v]++;
            sum += v;
        }
        int p18 = percentile(histogram, width * height, 0.18f);
        int mean = (int) (sum / Math.max(1, width * height));
        int threshold = clamp(Math.min(145, Math.max(45, Math.min(p18 + 18, mean - 35))), 35, 155);

        boolean[] dark = new boolean[width * height];
        for (int i = 0; i < dark.length; i++) dark[i] = (gray[i] & 0xFF) <= threshold;

        boolean[] seen = new boolean[dark.length];
        List<Component> candidates = new ArrayList<>();
        int minArea = Math.max(18, (width * height) / 18000);
        int maxArea = Math.max(minArea + 1, (width * height) / 20);
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int y = 2; y < height - 2; y++) {
            for (int x = 2; x < width - 2; x++) {
                int start = y * width + x;
                if (!dark[start] || seen[start]) continue;
                Component c = new Component();
                queue.clear();
                queue.add(start); seen[start] = true;
                while (!queue.isEmpty()) {
                    int index = queue.removeFirst();
                    int px = index % width, py = index / width;
                    c.add(px, py);
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            if (ox == 0 && oy == 0) continue;
                            int nx = px + ox, ny = py + oy;
                            if (nx < 1 || nx >= width - 1 || ny < 1 || ny >= height - 1) continue;
                            int ni = ny * width + nx;
                            if (dark[ni] && !seen[ni]) { seen[ni] = true; queue.addLast(ni); }
                        }
                    }
                    if (c.area > maxArea * 2) break;
                }

                if (c.area < minArea || c.area > maxArea) continue;
                int cw = c.width(), ch = c.height();
                if (cw < 5 || ch < 5) continue;
                float aspect = cw / (float) ch;
                if (aspect < 0.45f || aspect > 2.2f) continue;
                if (c.fill() < 0.42f) continue;
                candidates.add(c);
            }
        }

        if (candidates.size() < 4) return Result.fail("لم يتم العثور على العلامات الأربع.");
        candidates.sort(Comparator.comparingInt((Component c) -> c.area).reversed());
        if (candidates.size() > 14) candidates = new ArrayList<>(candidates.subList(0, 14));

        Component[] best = null;
        float bestScore = Float.MAX_VALUE;
        int n = candidates.size();
        for (int a = 0; a < n - 3; a++) for (int b = a + 1; b < n - 2; b++)
            for (int c = b + 1; c < n - 1; c++) for (int d = c + 1; d < n; d++) {
                Component[] ordered = order(candidates.get(a), candidates.get(b), candidates.get(c), candidates.get(d));
                if (ordered == null) continue;
                float score = score(ordered, width, height);
                if (score < bestScore) { bestScore = score; best = ordered; }
            }

        if (best == null || bestScore > 2.15f) return Result.fail("العلامات موجودة لكن هندسة 20×20 غير مؤكدة.");

        // TL inward = bottom-right extremum; TR inward = bottom-left;
        // BR inward = top-left; BL inward = top-right.
        Point tl = new Point(best[0].maxSumX, best[0].maxSumY);
        Point tr = new Point(best[1].maxYMinusXX, best[1].maxYMinusXY);
        Point br = new Point(best[2].minSumX, best[2].minSumY);
        Point bl = new Point(best[3].maxXMinusYX, best[3].maxXMinusYY);

        float top = distance(tl, tr), bottom = distance(bl, br);
        float left = distance(tl, bl), right = distance(tr, br);
        float minSide = Math.min(Math.min(top, bottom), Math.min(left, right));
        if (minSide < Math.min(width, height) * 0.08f) return Result.fail("Marker بعيد جدًا — قرّب الكاميرا.");

        float confidence = clamp01(1f - bestScore / 2.5f);
        return new Result(true, "", new Point[]{tl, tr, br, bl}, confidence);
    }

    private static Component[] order(Component a, Component b, Component c, Component d) {
        Component[] all = {a,b,c,d};
        float cx = 0, cy = 0;
        for (Component p : all) { cx += p.cx(); cy += p.cy(); }
        cx /= 4f; cy /= 4f;
        Component tl=null,tr=null,br=null,bl=null;
        for (Component p : all) {
            if (p.cx() < cx && p.cy() < cy) { if (tl != null) return null; tl=p; }
            else if (p.cx() >= cx && p.cy() < cy) { if (tr != null) return null; tr=p; }
            else if (p.cx() >= cx && p.cy() >= cy) { if (br != null) return null; br=p; }
            else { if (bl != null) return null; bl=p; }
        }
        return tl==null||tr==null||br==null||bl==null ? null : new Component[]{tl,tr,br,bl};
    }

    private static float score(Component[] p, int width, int height) {
        Point tl=new Point(p[0].cx(),p[0].cy()), tr=new Point(p[1].cx(),p[1].cy());
        Point br=new Point(p[2].cx(),p[2].cy()), bl=new Point(p[3].cx(),p[3].cy());
        float top=distance(tl,tr), bottom=distance(bl,br), left=distance(tl,bl), right=distance(tr,br);
        float meanSide=(top+bottom+left+right)/4f;
        if (meanSide < Math.min(width,height)*0.10f) return 99f;
        float opposite=(Math.abs(top-bottom)+Math.abs(left-right))/meanSide;
        float square=Math.abs((top+bottom)-(left+right))/(2f*meanSide);
        float diag1=distance(tl,br), diag2=distance(tr,bl);
        float diagonal=Math.abs(diag1-diag2)/Math.max(1f,(diag1+diag2)/2f);
        float meanArea=(p[0].area+p[1].area+p[2].area+p[3].area)/4f;
        float areaVar=0f;
        for(Component c:p) areaVar+=Math.abs(c.area-meanArea)/Math.max(1f,meanArea);
        areaVar/=4f;
        float centerX=(tl.x+tr.x+br.x+bl.x)/4f, centerY=(tl.y+tr.y+br.y+bl.y)/4f;
        float centerPenalty=(Math.abs(centerX-width/2f)/width + Math.abs(centerY-height/2f)/height)*0.35f;
        return opposite*0.9f + square*0.8f + diagonal*0.8f + areaVar*0.45f + centerPenalty;
    }

    private static float distance(Point a, Point b) {
        float dx=a.x-b.x, dy=a.y-b.y;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    private static int percentile(int[] histogram, int total, float q) {
        int target=Math.max(1,Math.round(total*q)), count=0;
        for(int i=0;i<histogram.length;i++){ count+=histogram[i]; if(count>=target)return i; }
        return 255;
    }

    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static float clamp01(float v){return Math.max(0f,Math.min(1f,v));}
}
