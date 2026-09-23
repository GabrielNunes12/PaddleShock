import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import com.paddleshock.achievements.Achievement;

/**
 * Generates the 256x256 Steam achievement icons - an unlocked (color) and a locked (gray) JPG per
 * {@link Achievement} - into {@code branding/achievements/}, named by Steam API name. Run from the
 * repo root after compiling, so the enum is on the classpath:
 *
 * <pre>./gradlew classes && java -cp build/classes/java/main tools/AchievementIconGen.java</pre>
 *
 * Upload each pair in Steamworks (App Admin > Stats &amp; Achievements) - see
 * {@code docs/steam-achievements.md}.
 */
public final class AchievementIconGen {

    private static final int SIZE = 256;
    private static final Color BACKGROUND = new Color(0x1A1D24);
    private static final Color PANEL_LINE = new Color(0x31353F);
    private static final Color ORANGE = new Color(0xE8823A);
    private static final Color TEXT = new Color(0xF5F6F8);
    private static final Color LOCKED_FILL = new Color(0x3A3E48);
    private static final Color LOCKED_TEXT = new Color(0x8B909C);

    /** Short badge text per achievement - legible at Steam's small 64px display size. */
    private static final Map<Achievement, String> LABELS = Map.ofEntries(
            Map.entry(Achievement.FIRST_WIN, "1ST"),
            Map.entry(Achievement.SHUTOUT, "0"),
            Map.entry(Achievement.CLOSE_CALL, "+1"),
            Map.entry(Achievement.WINS_25, "25"),
            Map.entry(Achievement.WINS_100, "100"),
            Map.entry(Achievement.BEAT_HARD_AI, "AI"),
            Map.entry(Achievement.ARENA_MASTER, "ALL"),
            Map.entry(Achievement.POWER_PLAYER, "PWR"),
            Map.entry(Achievement.TOUR_FIRST_BOSS, "BOSS"),
            Map.entry(Achievement.TOUR_COMPLETE, "TOUR"),
            Map.entry(Achievement.ONLINE_WIN, "VS"),
            Map.entry(Achievement.RANK_GOLD, "GOLD"),
            Map.entry(Achievement.RANK_DIAMOND, "DIA"),
            Map.entry(Achievement.FULL_KIT, "KIT"),
            Map.entry(Achievement.ALL_POWERUPS, "ARS"));

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "branding/achievements");
        Files.createDirectories(out);
        for (Achievement achievement : Achievement.values()) {
            String label = LABELS.get(achievement);
            if (label == null) {
                throw new IllegalStateException("No badge label for " + achievement + " - add one to LABELS");
            }
            ImageIO.write(render(label, true), "jpg", out.resolve(achievement.steamApiName() + ".jpg").toFile());
            ImageIO.write(render(label, false), "jpg", out.resolve(achievement.steamApiName() + "_locked.jpg").toFile());
        }
        System.out.println("Wrote " + Achievement.values().length * 2 + " icons to " + out.toAbsolutePath());
    }

    private static BufferedImage render(String label, boolean unlocked) {
        // TYPE_INT_RGB: JPG has no alpha channel.
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(PANEL_LINE);
        g.setStroke(new BasicStroke(6f));
        g.draw(new RoundRectangle2D.Double(9, 9, SIZE - 18, SIZE - 18, 40, 40));

        // Medal disc with a ring, and a small lightning bolt tab on top (the brand motif).
        Color fill = unlocked ? ORANGE : LOCKED_FILL;
        g.setColor(fill);
        g.fill(new Ellipse2D.Double(38, 44, 180, 180));
        g.setColor(unlocked ? TEXT : LOCKED_TEXT);
        g.setStroke(new BasicStroke(7f));
        g.draw(new Ellipse2D.Double(52, 58, 152, 152));

        Path2D bolt = new Path2D.Double();
        double[][] pts = {{134, 14}, {110, 50}, {126, 50}, {118, 76}, {146, 38}, {130, 38}, {140, 14}};
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) bolt.moveTo(pts[i][0], pts[i][1]); else bolt.lineTo(pts[i][0], pts[i][1]);
        }
        bolt.closePath();
        g.setColor(unlocked ? TEXT : LOCKED_TEXT);
        g.fill(bolt);

        // Largest bold font size that fits the label inside the ring.
        int fontSize = 96;
        FontMetrics metrics;
        do {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            metrics = g.getFontMetrics();
            fontSize -= 4;
        } while (metrics.stringWidth(label) > 118 && fontSize > 20);
        int x = 128 - metrics.stringWidth(label) / 2;
        int y = 134 + (metrics.getAscent() - metrics.getDescent()) / 2;
        g.setColor(unlocked ? BACKGROUND : LOCKED_TEXT);
        g.drawString(label, x, y);

        g.dispose();
        return img;
    }
}
