import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Run from the repository root: java art/GenerateMdtermIcon.java */
class GenerateMdtermIcon {
    public static void main(String[] args) throws Exception {
        BufferedImage original = ImageIO.read(new File("art/mdterm-icon.png"));
        int width = original.getWidth(), height = original.getHeight();
        BufferedImage cutout = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int minX = width, minY = height, maxX = 0, maxY = 0;
        // Remove only the white exterior of the supplied dark tile, preserving white lettering.
        for (int y = 0; y < height; y++) {
            int left = 0, right = width - 1;
            while (left < width && brightness(original.getRGB(left, y)) > 128) left++;
            while (right >= left && brightness(original.getRGB(right, y)) > 128) right--;
            if (left > right) continue;
            minX = Math.min(minX, left);
            maxX = Math.max(maxX, right);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            for (int x = left; x <= right; x++) cutout.setRGB(x, y, original.getRGB(x, y));
        }
        if (minX > maxX || minY > maxY) throw new IllegalArgumentException("No dark icon tile found");
        int size = Math.max(maxX - minX + 1, maxY - minY + 1);
        int cropX = (minX + maxX + 1 - size) / 2;
        int cropY = (minY + maxY + 1 - size) / 2;
        BufferedImage icon = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(cutout, 0, 0, 512, 512, cropX, cropY, cropX + size, cropY + size, null);
        graphics.dispose();
        File output = new File("app/src/main/res/drawable-nodpi/mdterm_icon.png");
        output.getParentFile().mkdirs();
        ImageIO.write(icon, "png", output);
        System.out.println("Generated " + output + " (512 x 512)");
        BufferedImage monochrome = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                int color = icon.getRGB(x, y);
                int brightest = Math.max((color >> 16) & 255, Math.max((color >> 8) & 255, color & 255));
                int alpha = Math.max(0, Math.min(255, (brightest - 100) * 255 / 120));
                alpha = alpha * ((color >>> 24) & 255) / 255;
                monochrome.setRGB(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        ImageIO.write(monochrome, "png", new File(output.getParentFile(), "mdterm_icon_monochrome.png"));
    }

    private static int brightness(int color) {
        return Math.min((color >> 16) & 255, Math.min((color >> 8) & 255, color & 255));
    }
}
