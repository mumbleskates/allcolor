import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

import javax.imageio.ImageIO;

public class ImgColor {

    private static class Point {
        int x, y;
        color c;

        Point(int x, int y, color c) {
            this.x = x;
            this.y = y;
            this.c = c;
        }
    }

    private static class color {
        char r, g, b;

        color(int i, int j, int k) {
            r = (char) i;
            g = (char) j;
            b = (char) k;
        }
    }

    private static LinkedList<Point> listFromImg(String path) throws IOException {
        LinkedList<Point> ret = new LinkedList<>();
        BufferedImage bi;
        bi = ImageIO.read(new File(path));
        assert bi != null;
        assert bi.getWidth() * bi.getHeight() <= 1<<24;
        assert Math.max(bi.getWidth(), bi.getHeight()) < 1<<15;
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                Color c = new Color(bi.getRGB(x, y));
                ret.add(new Point(x, y, new color(c.getRed(), c.getGreen(), c.getBlue())));
            }
        }
        Collections.shuffle(ret);
        return ret;
    }

    private static LinkedList<color> allColors() {
        LinkedList<color> colors = new LinkedList<>();
        for (int r = 0; r < 256; r++) {
            for (int g = 0; g < 256; g++) {
                for (int b = 0; b < 256; b++) {
                    colors.add(new color(r, g, b));
                }
            }
        }
        Collections.shuffle(colors);
        return colors;
    }

    private static Double cDelta(color a, color b) {
        return Math.pow(a.r - b.r, 2) + Math.pow(a.g - b.g, 2) + Math.pow(a.b - b.b, 2);
    }

    public static void main(String[] args) throws IOException {
        BufferedImage img = new BufferedImage(4096, 4096, BufferedImage.TYPE_INT_RGB);
        LinkedList<Point> orig = listFromImg(args[0]);
        LinkedList<color> toDo = allColors();

        Point p;
        while (orig.size() > 0 && (p = orig.pop()) != null) {
            color chosen = toDo.pop();
            for (int i = 0; i < Math.min(100, toDo.size()); i++) {
                color c = toDo.pop();
                if (cDelta(c, p.c) < cDelta(chosen, p.c)) {
                    toDo.add(chosen);
                    chosen = c;
                } else {
                    toDo.add(c);
                }
            }
            img.setRGB(p.x, p.y, new Color(chosen.r, chosen.g, chosen.b).getRGB());
        }
        try {
            ImageIO.write(img, "PNG", new File(args[1]));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
