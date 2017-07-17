import javax.imageio.ImageIO;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.function.IntFunction;


public class ColorDeposit {
    // map of our image: -1 for unexplored, -2 for frontier,
    // non-negative for a placed color at that index in colors
    private static int[] canvas;
    private static ImplicitKdTree<Integer> frontier;
    private static int[] colors;
    private static double[][] values;
    private static final int w = 4096;
    private static final int h = 4096;

    private static Point[] frontierOffsets = {
            new Point(-1, 0),
            new Point(0, -1),
            new Point(1, 0),
            new Point(0, 1),
    };
    private static Point[] sampleOffsets = {
            new Point(-1,  0),
            new Point(-1, -1),
            new Point(0, -1),
            new Point(1, -1),
            new Point(1,  0),
            new Point(1,  1),
            new Point(0,  1),
            new Point(-1,  1),
    };
    private static double[] sampleWeights;
    static {
        sampleWeights = new double[sampleOffsets.length];
        int i = 0;
        for (Point p : sampleOffsets) sampleWeights[i++] = p.distanceSq(0, 0);
    }


    private static final long startTime = System.nanoTime();

    private static void log(Object log) {
        System.out.printf("%10.6f %s%n", (System.nanoTime() - startTime) / 1e9, log);
    }

    private static void shuffleIntArray(int[] a, Random r) {
        int swap;
        int j;
        for (int i = a.length - 1; i > 0; i--) {
            j = r.nextInt(i+1);
            swap = a[i];
            a[i] = a[j];
            a[j] = swap;
        }
    }

    private static int[] allColors() {
        int[] result = new int[1<<24];
        for (int i = 0; i < 1<<24; i++) {
            result[i] = i | 0xff000000;
        }
        return result;
    }

    private interface ColorConverter {
        void convert(int[] colors, double[][] result, int from, int to);
    }

    private static final ColorConverter getSrgbValues = (colors, result, from, to) -> {
        for (int ci = from; ci < to; ci++) {
            int color = colors[ci];
            result[ci] = new double[] {
                    ((color & 0x00ff0000) >> 16) / 256.0,  // r
                    ((color & 0x0000ff00) >> 8) / 256.0,  // g
                    (color & 0x000000ff) / 256.0  // b
            };
        }
    };

    private static final ColorConverter getLinearRgbValues = (colors, result, from, to) -> {
        getSrgbValues.convert(colors, result, from, to);
        for (int ci = from; ci < to; ci++) {
            double[] v = result[ci];
            int i = 0;
            for (double ch : v) {
                v[i++] = ch <= 0.04045
                        ? ch / 12.92
                        : Math.pow((ch + 0.055) / 1.055, 2.4);
            }
        }
    };

    private static final ColorConverter getXyzValues = (colors, result, from, to) -> {
        getLinearRgbValues.convert(colors, result, from, to);
        for (int ci = from; ci < to; ci++) {
            double[] v = result[ci];
            double r = v[0], g = v[1], b = v[2];
            v[0] = r * 0.412424 + g * 0.212656 + b * 0.0193324;
            v[1] = r * 0.357579 + g * 0.715158 + b * 0.119193;
            v[2] = r * 0.180464 + g * 0.0721856 + b * 0.950444;
        }
    };

    private static final ColorConverter getLabValues = (colors, result, from, to) -> {
        getXyzValues.convert(colors, result, from, to);
        for (int ci = from; ci < to; ci++) {
            double[] v = result[ci];
            // scale channel values first
            int i = 0;
            for (double ch : v) {
                v[i++] = ch > 216.0 / 24389.0
                        ? Math.cbrt(ch)
                        : ch * 7.787 + 16.0/116.0;
            }
            // final conversion to LAB
            double x = v[0], y = v[1], z = v[2];
            v[0] = y * 116.0 - 16.0;
            v[1] = (x - y) * 500.0;
            v[2] = (y - z) * 200.0;
        }
    };


    private static void placePixel(int index, int colorIndex) {
        canvas[index] = colorIndex;
        int x = index % w;
        int y = index / w;
        // find empty neighboring points as frontiers
        for (Point frontierOffset : frontierOffsets) {
            int fx = x + frontierOffset.x;
            int fy = y + frontierOffset.y;
//            // hard borders
//            if (fx < 0 || fx >= w) continue;
//            if (fy < 0 || fy >= h) continue;
            // wraparound borders
            if (fx < 0 || fx >= w) fx = (fx + w) % w;
            if (fy < 0 || fy >= h) fy = (fy + h) % h;
            if (canvas[fy * w + fx] < 0) {
                // canvas[fy][fx] = -2;
                // sum surrounding points for the new value of this frontier
                double[] newVal = new double[3];
                double totalWeight = 0;
                for (int i = 0; i < sampleOffsets.length; i++) {
                    Point sampleOffset = sampleOffsets[i];
                    int sx = fx + sampleOffset.x;
                    if (sx < 0 || sx >= w) sx = (sx + w) % w;
                    int sy = fy + sampleOffset.y;
                    if (sy < 0 || sy >= h) sy = (sy + h) % h;
                    // if a color was already placed here, add it to the sample average
                    int sampleSpot = canvas[sy * w + sx];
                    if (sampleSpot >= 0) {
                        double sampleWeight = sampleWeights[i];
                        totalWeight += sampleWeight;
                        double[] sampleValue = values[sampleSpot];
                        for (int ch = 0; ch < 3; ch++) {
                            newVal[ch] += sampleValue[ch] * sampleWeight;
                        }
                    }
                }
                // normalize value from totalWeight
                for (int ch = 0; ch < 3; ch++) {
                    newVal[ch] /= totalWeight;
                }
                // update this frontier point
                frontier.put(fy * w + fx, newVal);
            }
        }
    }

    private static ImplicitKdTree.NearestResult<Integer>[] findings;
    private static ImplicitKdTree.NearestResult<Integer>[] inputting;

    private static Runnable searchWorker(
            final int workerId,
            final int POOL_SIZE,
            final int BATCH_SIZE,
            final CyclicBarrier sync
    ) { return () -> {
        // convert colors
        getLabValues.convert(
                colors, values,
                workerId * values.length / POOL_SIZE,
                (workerId + 1) * values.length / POOL_SIZE
        );
        // first sync on color value calculation
        try {
            sync.await();
        } catch (BrokenBarrierException | InterruptedException ex) {
            return;
        }

        final int destOffset = workerId * BATCH_SIZE;

        // for each batch
        for (int sourceOffset = workerId * BATCH_SIZE;
             sourceOffset < values.length - BATCH_SIZE;
             sourceOffset += (POOL_SIZE - 1) * BATCH_SIZE) {
            ImplicitKdTree.NearestResult<Integer>[] dest = findings;
            // process our pixels for the batch
            for (int i = 0; i < BATCH_SIZE; i++) {
                dest[i + destOffset] = frontier.nearest(values[sourceOffset + i]);
            }
            // sync
            try {
                sync.await();
            } catch (BrokenBarrierException | InterruptedException ex) {
                return;
            }
        }
    }; }

    public static void main(String[] args) throws IOException {
        final int POOL_SIZE = Runtime.getRuntime().availableProcessors();
        final int BATCH_SIZE = 128;
        final Random rand = new Random();

        // parameters here
        final int originX = w / 2;
        final int originY = h / 2;

        //noinspection unchecked
        findings = new ImplicitKdTree.NearestResult[(POOL_SIZE - 1) * BATCH_SIZE];
        //noinspection unchecked
        inputting = new ImplicitKdTree.NearestResult[(POOL_SIZE - 1) * BATCH_SIZE];
        final ImplicitKdTree<Integer> shortFrontier;
        final CyclicBarrier sync = new CyclicBarrier(POOL_SIZE, () -> {
            if (shortFrontier == null) return;
            for (ImplicitKdTree.NearestResult<Integer> placed : inputting) {
                frontier.remove(placed.key);
            }
            shortFrontier.forEach((Integer p, double[] val) -> frontier.put(p, val));
        });


        log("creating colors");
        colors = allColors();
        log("shuffling");
        shuffleIntArray(colors, rand);
        // truncate
        colors = Arrays.copyOf(colors, w * h);

        log("computing color values");
        values = new double[w * h][];
        // getLabValues.convert(colors, values, 0, w * h); // todo: put this in first worker cycle

        log("calculating color envelope");
        double[] envelopeLower = new double[3];
        double[] envelopeUpper = new double[3];
        Arrays.fill(envelopeLower, Double.POSITIVE_INFINITY);
        Arrays.fill(envelopeUpper, Double.NEGATIVE_INFINITY);
        for (double[] value : values) {
            for (int ch = 0; ch < 3; ch++) {
                envelopeLower[ch] = Math.min(envelopeLower[ch], value[ch]);
                envelopeUpper[ch] = Math.max(envelopeUpper[ch], value[ch]);
            }
        }
        log("color value envelope:");
        log("lower=" + Arrays.toString(envelopeLower));
        log("upper=" + Arrays.toString(envelopeUpper));

        log("building canvas");
        canvas = new int[w * h];
        Arrays.fill(canvas, -1);

        log("processing");

        // place first pixel
        frontier = new ImplicitKdTree<>(3, envelopeLower, envelopeUpper);
        shortFrontier = new ImplicitKdTree<>(3, envelopeLower, envelopeUpper);
        placePixel(originY * w + originX, 0);

        long beganWorkingTime = System.nanoTime();
        long lastUpdateTime = beganWorkingTime;
        int lastUpdateIndex = 1;
        int nextUpdateIndex = 1024;
        final double targetUpdateInterval = 0.5;
        long nodesSearched = 0;

        // serially place all remaining colors
        for (int i = 1; i < colors.length; i++) {
            ImplicitKdTree.NearestResult<Integer> result = frontier.nearest(values[i]);
            Integer best = result.key;
            nodesSearched += result.searched;
            frontier.remove(best);
            placePixel(best, i);

            // print progress
            if (i == nextUpdateIndex) {
                long timeNow = System.nanoTime();
                double secondsElapsed = (double)(timeNow - beganWorkingTime) / 1e9;
                double secondsSinceLastUpdate = (double)(timeNow - lastUpdateTime) / 1e9;
                double updateRate = (i - lastUpdateIndex) / secondsSinceLastUpdate;
                double harmonicAvgUpdateRate = 2 / (1 / updateRate + secondsElapsed / i);
                double percent = (double)i / colors.length * 100;
                nodesSearched = 0;
                System.out.printf(
                        "-> %.1f elapsed - %.3f%% - eta %.1f sec - %.0f px/sec - frontier size %d, height %d - %d node/px, %.1f node/sec     \r",
                        secondsElapsed,
                        percent,
                        (colors.length - i) / harmonicAvgUpdateRate,  // ETA: remaining work to do divided by rate so far
                        updateRate,
                        frontier.size(),
                        frontier.maxHeight(),
                        nodesSearched / (i - lastUpdateIndex),
                        nodesSearched / secondsSinceLastUpdate
                );
                lastUpdateTime = timeNow;
                lastUpdateIndex = i;
                nextUpdateIndex = i + (int)(harmonicAvgUpdateRate * targetUpdateInterval);
            }
        }

        long finishedWorkingTime = System.nanoTime();
        System.out.println();
        log("done computing");

        double secondsElapsed = (double)(finishedWorkingTime - beganWorkingTime) / 1e9;
        System.out.printf(
                "--- %.2f seconds calculating, average %.1f px/sec%n",
                secondsElapsed, colors.length / secondsElapsed
        );

        log("verifying");
        int[] presents = new int[colors.length];
        for (int color : canvas) {
            presents[color] = 1;
        }
        int distinct = 0;
        for (int present : presents) distinct += present;
        log(distinct == w * h
                ? "Verified! All colors distinct"
                : "ERROR! duplicate colors exist");
        
        log("filling image");
        // allocate image
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] output = ((DataBufferInt)img.getRaster().getDataBuffer()).getData();
        int i = 0;
        for (int color : canvas) {
            output[i++] = colors[color];
        }

        log("writing output file");
        ImageIO.write(img, "PNG", new File(args[0]));

        log("Done!");
    }

}
