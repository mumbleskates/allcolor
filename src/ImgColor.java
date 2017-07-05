import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;


public class ImgColor {
    private static BufferedImage img;
    private static long[] points;
    private static int pointsRemaining;
    private static int[] colors;
    private static int[] alternate;
    private static int colorsRemaining;
    private static int colorsPos;
    private static int colorsLen;
    private static int alternatePos;
    private static ReentrantLock lock;
    private static CyclicBarrier flipBarrier;
    private static CountDownLatch finished;

    private static final long startTime = System.nanoTime();

    private static void log(Object log) {
        System.out.printf("%10.6f %s%n", (System.nanoTime() - startTime) / 1e9, log);
    }

    private static void shuffleLongArray(long[] a, Random r) {
        long swap;
        int j;
        for (int i = a.length - 1; i > 0; i--) {
            j = r.nextInt(i+1);
            swap = a[i];
            a[i] = a[j];
            a[j] = swap;
        }
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

    private static long packPoint(int x, int y) {
        return (long)x | ((long)y << 32);
    }

    private static int getColorFromImage(long point, BufferedImage src) {
        return src.getRGB((int)point, (int)(point >> 32));
    }

    private static void setColorOnImage(long point, int color, BufferedImage dest) {
        dest.setRGB((int)point, (int)(point >> 32), color);
    }

    private static int getColorDistance(int a, int b) {
        int dr = (((a & 0xff0000) - (b & 0xff0000)) >> 16);
        int dg = (((a & 0x00ff00) - (b & 0x00ff00)) >> 8);
        int db =  ((a & 0x0000ff) - (b & 0x0000ff));
        return dr*dr + dg*dg + db*db;
    }

    private static BufferedImage loadAcceptableImage(String path) throws IOException {
        BufferedImage bi = ImageIO.read(new File(path));
        if (bi.getWidth() * bi.getHeight() > 1<<24) {
            throw new RuntimeException("Image cannot be over 1024*1024*16 pixels total");
        }
        return bi;
    }

    private static long[] getImagePoints(BufferedImage bi) throws IOException {
        long[] ret;
        int pixelCount = bi.getWidth() * bi.getHeight();
        ret = new long[pixelCount];
        int i = 0;
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                ret[i++] = packPoint(x, y);
            }
        }
        return ret;
    }

    private static int[] allColors() {
        int[] ret = new int[1<<24];
        for (int i = 0; i < 1<<24; i++) {
            ret[i] = i | 0xff000000;
        }
        return ret;
    }

    private static int getSingleNextColor() {
        if (colorsPos >= colorsLen) {
            // we have reached the end of our colors; swap colors and alternate
            int[] swap = colors;
            colors = alternate;
            alternate = swap;
            // restart counters, set length of colors to the count written to alternate
            colorsPos = 0;
            colorsLen = alternatePos;
            alternatePos = 0;
        }
        return colors[colorsPos++];
    }

    private static void processSinglePoint(int comparisons) {
        // all threads are now waiting on the barrier, we can work safely
        final long originalPoint = points[--pointsRemaining];
        final int originalColor = getColorFromImage(originalPoint, img);

        int bestColor = getSingleNextColor();
        colorsRemaining--;
        int bestScore = getColorDistance(bestColor, originalColor);

        final int addlComparisons = Math.min(comparisons - 1, pointsRemaining);
        for (int tries = 0; tries < addlComparisons; tries++) {
            // get next todo color
            int tryColor = getSingleNextColor();
            // dump the less terrible of the two colors into alternate
            int tryScore = getColorDistance(tryColor, originalColor);
            if (tryScore < bestScore) {
                alternate[alternatePos++] = bestColor;
                bestColor = tryColor;
                bestScore = tryScore;
            } else {
                alternate[alternatePos++] = tryColor;
            }
        }

        setColorOnImage(originalPoint, bestColor, img);
    }

    public static void main(String[] args) throws IOException {
        final int POOL_SIZE = Runtime.getRuntime().availableProcessors();

        Random rand = new Random();
        log("Loading");
        img = loadAcceptableImage(args[0]);

        log("Counting original image");
        alternate = new int[1<<24];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                alternate[img.getRGB(x, y) & 0xffffff] = 1;
            }
        }
        int distinct = 0;
        for (int present : alternate) distinct += present;
        double originalDistinctRatio = (double)distinct / (img.getWidth() * img.getHeight());
        log("Total " + distinct + " colors in original image " +
                "(" + originalDistinctRatio + " fill ratio)");

        final int comparisons;
        if (args.length < 3 || args[2].toLowerCase().equals("auto")) {
            comparisons = Math.min(10000, Math.max(2, (int)(1 / originalDistinctRatio)));
            log("Choosing each pixel from " + comparisons + " (auto) colors");
        } else {
            comparisons = Math.min(1<<24, Math.max(1, Integer.parseInt(args[2])));
            log("Choosing each pixel from " + comparisons + " colors");
        }

        log("Building points and colors");
        points = getImagePoints(img);
        pointsRemaining = points.length;
        colors = allColors();
        colorsRemaining = colors.length;
        log("Shuffling");
        shuffleLongArray(points, rand);
        shuffleIntArray(colors, rand);

        log("Processing");

        final Runnable worker = () -> {
            while (true) {
                boolean waitForFlip;
                int threadColorsPos = -1;
                int threadAlternatePos = -1;
                long originalPoint = -1;

                lock.lock();
                try {
                    if (pointsRemaining == 0) {
                        // no more pixels to write
                        finished.countDown();
                        return;
                    }
                    if (colorsPos + comparisons > colorsLen) {
                        waitForFlip = true;
                    } else {
                        waitForFlip = false;
                        threadColorsPos = colorsPos;
                        colorsPos += comparisons;
                        threadAlternatePos = alternatePos;
                        alternatePos += comparisons - 1;
                        colorsRemaining--;
                        originalPoint = points[--pointsRemaining];
                    }
                } finally { lock.unlock(); }

                if (waitForFlip) {
                    try {
                        flipBarrier.await();
                    } catch (BrokenBarrierException | InterruptedException ex) {
                        return;
                    }
                } else {
                    // get original and first todo color
                    int originalColor = getColorFromImage(originalPoint, img);
                    int bestColor = colors[threadColorsPos++];
                    int bestScore = getColorDistance(bestColor, originalColor);

                    // compare against COMPARISONS - 1 other colors
                    for (int i = 1; i < comparisons; i++) {
                        int tryColor = colors[threadColorsPos++];
                        int tryScore = getColorDistance(tryColor, originalColor);
                        if (tryScore < bestScore) {
                            alternate[threadAlternatePos++] = bestColor;
                            bestColor = tryColor;
                            bestScore = tryScore;
                        } else {
                            alternate[threadAlternatePos++] = tryColor;
                        }
                    }

                    setColorOnImage(originalPoint, bestColor, img);
                }
            }
        };

        final Runnable flipArrays = () -> {
            do {
                processSinglePoint(comparisons);
            } while (pointsRemaining > 0 && colorsRemaining < comparisons);
        };

        colorsPos = 0;
        colorsLen = colors.length;
        alternatePos = 0;
        pointsRemaining = colors.length;
        lock = new ReentrantLock();
        flipBarrier = new CyclicBarrier(POOL_SIZE, flipArrays);
        finished = new CountDownLatch(POOL_SIZE);
        Thread[] pool = new Thread[POOL_SIZE];
        for (int i = 0; i < POOL_SIZE; i++) {
            pool[i] = new Thread(worker);
            pool[i].start();
        }
        try {
            finished.await();
        } catch (InterruptedException ex) {
            log("Interrupted");
            return;
        }

        log("Verifying");
        Arrays.fill(colors, 0);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                colors[img.getRGB(x, y) & 0xffffff] = 1;
            }
        }
        distinct = 0;
        for (int present : colors) distinct += present;
        log(distinct == img.getWidth() * img.getHeight()
                ? "Verified! All colors distinct"
                : "ERROR! duplicate colors exist");

        log("Writing output");
        try {
            ImageIO.write(img, "PNG", new File(args[1]));
        } catch (IOException e) {
            e.printStackTrace();
        }
        log("Done!");
    }

}
