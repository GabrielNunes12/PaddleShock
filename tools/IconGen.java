import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Generates the app icon from one set of vector shapes (no external tools needed):
 *
 * <pre>java tools/IconGen.java [outputDir]    (default: branding/)</pre>
 *
 * Writes paddleshock.ico (Windows exe / Steam client icon, PNG-compressed 16-256px entries),
 * paddleshock.png (512px, Linux launcher) and paddleshock-icon.svg (same artwork, for editing).
 * The jpackage task in build.gradle.kts picks up the .ico/.png automatically.
 *
 * Artwork: a table-tennis paddle tilted 40 degrees with a lightning bolt on its face and a ball
 * beside it, in the Theme.java palette. Geometry is authored on a 256x256 grid.
 */
public final class IconGen {

    // Theme.java colors (see branding/README.md for the float -> hex table).
    private static final Color BACKGROUND = new Color(0x1A1D24);
    private static final Color PANEL_LINE = new Color(0x31353F);
    private static final Color ORANGE = new Color(0xE8823A);
    private static final Color TEXT = new Color(0xF5F6F8);
    /** Handle wood tone - ORANGE lifted toward TEXT, so it stays in-palette but reads as wood. */
    private static final Color WOOD = new Color(0xF2BE8E);

    private static final double GRID = 256;
    private static final double TILT_DEG = 40;

    // Paddle, authored upright, then rotated about the grid center. The head is a slightly tall
    // oval and the handle flares into it (a thin stick + round rim reads as a magnifying glass).
    private static final double HEAD_CX = 136, HEAD_CY = 100, HEAD_RX = 74, HEAD_RY = 80;
    private static final double[][] HANDLE = {
        {108, 150}, {164, 150}, {154, 186}, {154, 232}, {118, 232}, {118, 186},
    };
    private static final double HANDLE_CAP_R = 18;

    // The ball, top-left, clear of the paddle.
    private static final double BALL_CX = 56, BALL_CY = 60, BALL_R = 22;

    // Lightning bolt, drawn unrotated, centered on the (rotated) paddle head.
    private static final double[][] BOLT = {
        {10, -52}, {-26, 6}, {-2, 6}, {-12, 52}, {26, -8}, {2, -8}, {14, -52},
    };

    private static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "branding");
        Files.createDirectories(out);

        List<byte[]> pngs = new ArrayList<>();
        for (int size : ICO_SIZES) {
            pngs.add(png(render(size)));
        }
        Files.write(out.resolve("paddleshock.ico"), ico(ICO_SIZES, pngs));
        Files.write(out.resolve("paddleshock.png"), png(render(512)));
        Files.writeString(out.resolve("paddleshock-icon.svg"), svg());
        System.out.println("Wrote paddleshock.ico, paddleshock.png, paddleshock-icon.svg to " + out.toAbsolutePath());
    }

    private static double[] headCenter() {
        double[] p = {HEAD_CX, HEAD_CY};
        AffineTransform.getRotateInstance(Math.toRadians(TILT_DEG), GRID / 2, GRID / 2).transform(p, 0, p, 0, 1);
        return p;
    }

    private static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / GRID, size / GRID);

        // Background tile with a thin inset frame (frame dropped at tiny sizes, where it's just mud).
        g.setColor(BACKGROUND);
        g.fill(new RoundRectangle2D.Double(0, 0, GRID, GRID, 104, 104));
        if (size >= 48) {
            g.setColor(PANEL_LINE);
            g.setStroke(new BasicStroke(6f));
            g.draw(new RoundRectangle2D.Double(9, 9, GRID - 18, GRID - 18, 88, 88));
        }

        g.setColor(TEXT);
        g.fill(new Ellipse2D.Double(BALL_CX - BALL_R, BALL_CY - BALL_R, BALL_R * 2, BALL_R * 2));

        AffineTransform base = g.getTransform();
        g.rotate(Math.toRadians(TILT_DEG), GRID / 2, GRID / 2);
        g.setColor(WOOD);
        g.fill(polygon(HANDLE, 0, 0));
        g.fill(new Ellipse2D.Double(136 - HANDLE_CAP_R, 232 - HANDLE_CAP_R, HANDLE_CAP_R * 2, HANDLE_CAP_R * 2));
        g.setColor(ORANGE);
        g.fill(new Ellipse2D.Double(HEAD_CX - HEAD_RX, HEAD_CY - HEAD_RY, HEAD_RX * 2, HEAD_RY * 2));
        g.setTransform(base);

        double[] c = headCenter();
        g.setColor(TEXT);
        g.fill(polygon(BOLT, c[0], c[1]));

        g.dispose();
        return img;
    }

    private static Path2D polygon(double[][] points, double dx, double dy) {
        Path2D path = new Path2D.Double();
        for (int i = 0; i < points.length; i++) {
            if (i == 0) path.moveTo(points[i][0] + dx, points[i][1] + dy);
            else path.lineTo(points[i][0] + dx, points[i][1] + dy);
        }
        path.closePath();
        return path;
    }

    private static String points(double[][] points, double dx, double dy) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : points) {
            sb.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", p[0] + dx, p[1] + dy));
        }
        return sb.toString().trim();
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    /** ICO container with PNG-compressed entries (supported since Windows Vista). */
    private static byte[] ico(int[] sizes, List<byte[]> pngs) {
        int headerLen = 6 + 16 * sizes.length;
        int total = headerLen + pngs.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerLen;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            buf.put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        pngs.forEach(buf::put);
        return buf.array();
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String svg() {
        double[] c = headCenter();
        return String.format(java.util.Locale.ROOT, """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- Generated by tools/IconGen.java - edit the geometry there, then re-run it. -->
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256">
              <title>PaddleShock icon</title>
              <rect x="0" y="0" width="256" height="256" rx="52" fill="%s"/>
              <rect x="9" y="9" width="238" height="238" rx="44" fill="none" stroke="%s" stroke-width="6"/>
              <circle cx="%.0f" cy="%.0f" r="%.0f" fill="%s"/>
              <g transform="rotate(%.0f 128 128)">
                <polygon points="%s" fill="%s"/>
                <circle cx="136" cy="232" r="%.0f" fill="%s"/>
                <ellipse cx="%.0f" cy="%.0f" rx="%.0f" ry="%.0f" fill="%s"/>
              </g>
              <polygon points="%s" fill="%s"/>
            </svg>
            """,
            hex(BACKGROUND), hex(PANEL_LINE),
            BALL_CX, BALL_CY, BALL_R, hex(TEXT),
            TILT_DEG,
            points(HANDLE, 0, 0), hex(WOOD), HANDLE_CAP_R, hex(WOOD),
            HEAD_CX, HEAD_CY, HEAD_RX, HEAD_RY, hex(ORANGE),
            points(BOLT, c[0], c[1]), hex(TEXT));
    }
}
