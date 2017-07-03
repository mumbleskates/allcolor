import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;


public class ImgColor {

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

    public static void main(String[] args) throws IOException {
        final int COMPARISONS = 100;

        Random rand = new Random();
        System.out.println("Loading...");
        BufferedImage img = loadAcceptableImage(args[0]);
        System.out.println("Building points and colors...");
        long[] points = getImagePoints(img);
        int[] todo = allColors();
        int[] alternate = new int[todo.length];
        System.out.println("Shuffling...");
        shuffleLongArray(points, rand);
        shuffleIntArray(todo, rand);

        System.out.println("Processing...");

        int todoPos = 0;
        int todoLen = todo.length;
        int alternatePos = 0;

        int remaining = todo.length;
        for (long p : points) {
            int originalColor = getColorFromImage(p, img);
            int bestTodoColor = 0;
            int bestScore = Integer.MAX_VALUE;

            for (int tries = 0; tries < Math.min(COMPARISONS, remaining); tries++) {
                // get next todo color
                if (todoPos >= todoLen) {
                    // we have reached the end of our todo; swap todo and alternate
                    int[] swap = todo;
                    todo = alternate;
                    alternate = swap;
                    // restart counters, set length of todo to the count written to alternate
                    todoPos = 0;
                    todoLen = alternatePos;
                    alternatePos = 0;
                }
                int tryColor = todo[todoPos++];
                // dump the less terrible of the two colors into alternate
                int tryScore = getColorDistance(tryColor, originalColor);
                if (tryScore < bestScore) {
                    alternate[alternatePos++] = bestTodoColor;
                    bestTodoColor = tryColor;
                    bestScore = tryScore;
                } else {
                    alternate[alternatePos++] = tryColor;
                }
            }

            setColorOnImage(p, bestTodoColor, img);
            remaining--;
        }

        System.out.println("Writing output...");
        try {
            ImageIO.write(img, "PNG", new File(args[1]));
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Done!");
    }

}
