import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

/**
 * Rasterizes Anton into the AngelCode BMFont atlas jME/Lemur load as the game's UI font
 * ({@code src/main/resources/Interface/Fonts/Default.fnt} + {@code Default.png}):
 *
 * <pre>java tools/FontGen.java [ttf] [outputDir]</pre>
 *
 * (defaults: {@code tools/fonts/Anton-Regular.ttf}, {@code src/main/resources/Interface/Fonts}).
 *
 * Covers printable ASCII, all of Latin-1 (every accented letter Brazilian Portuguese - and most
 * Western European languages - needs) and common typographic punctuation. The original atlas had
 * ASCII only, so every accented PT-BR character rendered as nothing. Same 96px size, face name and
 * white-on-transparent layout as before, so existing text sizes and layouts are unchanged.
 */
public final class FontGen {

    private static final int SIZE = 96;
    private static final int ATLAS_WIDTH = 2048;
    private static final int SPACING = 2;
    /** Transparent margin kept around each glyph so anti-aliased edges are never clipped
     *  (the original atlas padded similarly). Doesn't affect advances, so layout is unchanged. */
    private static final int PAD = 3;

    public static void main(String[] args) throws IOException, FontFormatException {
        Path ttf = Path.of(args.length > 0 ? args[0] : "tools/fonts/Anton-Regular.ttf");
        Path out = Path.of(args.length > 1 ? args[1] : "src/main/resources/Interface/Fonts");

        Font font = Font.createFont(Font.TRUETYPE_FONT, ttf.toFile()).deriveFont(Font.PLAIN, (float) SIZE);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        int ascent = Math.round(font.getLineMetrics("Hg", frc).getAscent());
        int lineHeight = Math.round(font.getLineMetrics("Hg", frc).getHeight());

        List<Integer> codePoints = new ArrayList<>();
        IntStream.rangeClosed(32, 126).forEach(codePoints::add);
        IntStream.rangeClosed(160, 255).forEach(codePoints::add);
        for (int cp : new int[] {0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026, 0x20AC}) {
            codePoints.add(cp);
        }

        // Measure and pack glyphs row by row.
        record Glyph(int cp, Rectangle bounds, int advance, int x, int y) {
        }
        List<Glyph> glyphs = new ArrayList<>();
        int penX = 0;
        int penY = 0;
        int rowHeight = 0;
        for (int cp : codePoints) {
            if (!font.canDisplay(cp)) {
                System.out.println("skipping U+" + Integer.toHexString(cp) + " (not in font)");
                continue;
            }
            GlyphVector gv = font.createGlyphVector(frc, new String(Character.toChars(cp)));
            Rectangle tight = gv.getGlyphPixelBounds(0, frc, 0, 0);
            Rectangle bounds = tight.isEmpty() ? tight
                    : new Rectangle(tight.x - PAD, tight.y - PAD, tight.width + 2 * PAD, tight.height + 2 * PAD);
            int advance = Math.round(gv.getGlyphMetrics(0).getAdvance());
            if (penX + bounds.width + SPACING > ATLAS_WIDTH) {
                penX = 0;
                penY += rowHeight + SPACING;
                rowHeight = 0;
            }
            glyphs.add(new Glyph(cp, bounds, advance, penX, penY));
            penX += bounds.width + SPACING;
            rowHeight = Math.max(rowHeight, bounds.height);
        }
        int atlasHeight = (penY + rowHeight + SPACING + 63) / 64 * 64;

        BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setFont(font);
        g.setColor(Color.WHITE);
        for (Glyph glyph : glyphs) {
            // Draw with the glyph's bounding box top-left landing on its atlas cell.
            g.drawString(new String(Character.toChars(glyph.cp())), glyph.x() - glyph.bounds().x, glyph.y() - glyph.bounds().y);
        }
        g.dispose();

        Files.createDirectories(out);
        ImageIO.write(atlas, "png", out.resolve("Default.png").toFile());
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out.resolve("Default.fnt"), StandardCharsets.UTF_8))) {
            w.printf("info face=\"Default\" size=%d bold=0 italic=0 charset=\"\" unicode=1 stretchH=100 smooth=1 aa=1 padding=0,0,0,0 spacing=%d,%d%n",
                    SIZE, SPACING, SPACING);
            w.printf("common lineHeight=%d base=%d scaleW=%d scaleH=%d pages=1 packed=0%n", lineHeight, ascent, ATLAS_WIDTH, atlasHeight);
            w.println("page id=0 file=\"Default.png\"");
            w.printf("chars count=%d%n", glyphs.size());
            for (Glyph glyph : glyphs) {
                Rectangle b = glyph.bounds();
                w.printf("char id=%-5d x=%-5d y=%-5d width=%-4d height=%-4d xoffset=%-4d yoffset=%-4d xadvance=%-4d page=0  chnl=15%n",
                        glyph.cp(), glyph.x(), glyph.y(), b.width, b.height, b.x, ascent + b.y, glyph.advance());
            }
        }
        System.out.println("Wrote " + glyphs.size() + " glyphs, atlas " + ATLAS_WIDTH + "x" + atlasHeight
                + ", lineHeight " + lineHeight + ", base " + ascent + " to " + out.toAbsolutePath());
    }
}
