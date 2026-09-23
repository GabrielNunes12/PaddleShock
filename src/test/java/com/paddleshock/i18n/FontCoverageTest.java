package com.paddleshock.i18n;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.paddleshock.data.Catalog;
import com.paddleshock.data.Credits;
import com.paddleshock.data.ItemDefinition;
import com.paddleshock.tour.TourOpponent;
import com.paddleshock.tour.WorldTour;

/**
 * Every character the UI can display must exist in the bitmap font - a missing glyph renders as
 * nothing, which is how every accented PT-BR letter silently vanished until the font was
 * regenerated with Latin-1 (see tools/FontGen.java).
 */
class FontCoverageTest {

    private static Set<Integer> fontGlyphs() throws IOException {
        Set<Integer> ids = new TreeSet<>();
        try (InputStream in = FontCoverageTest.class.getResourceAsStream("/Interface/Fonts/Default.fnt")) {
            String fnt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("^char id=(\\d+)", Pattern.MULTILINE).matcher(fnt);
            while (m.find()) {
                ids.add(Integer.parseInt(m.group(1)));
            }
        }
        return ids;
    }

    private static List<String> allDisplayableText() throws IOException {
        List<String> texts = new ArrayList<>();
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = new Properties();
            try (InputStream in = FontCoverageTest.class.getResourceAsStream("/i18n/" + file)) {
                strings.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            strings.values().forEach(v -> texts.add((String) v));
        }
        Catalog.PADDLES.stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        Catalog.TABLES.stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        Catalog.BALLS.stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        Catalog.POWERUPS.stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        Catalog.LEVELS.stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        Catalog.LEVELS.forEach(l -> texts.add(l.getTagline()));
        for (String category : List.of("skin", "trail", "celebration")) {
            Catalog.cosmeticsFor(category).stream().map(ItemDefinition::getDisplayName).forEach(texts::add);
        }
        WorldTour.OPPONENTS.stream().map(TourOpponent::name).forEach(texts::add);
        Credits.modelsSection().lines().forEach(l -> texts.add(l.text()));
        Credits.otherSections().forEach(s -> s.lines().forEach(l -> texts.add(l.text())));
        return texts;
    }

    @Test
    void theFontHasAGlyphForEveryCharacterTheUiShows() throws IOException {
        Set<Integer> glyphs = fontGlyphs();
        Set<String> missing = new TreeSet<>();
        for (String text : allDisplayableText()) {
            text.codePoints()
                    .filter(cp -> cp != '\n')
                    .filter(cp -> !glyphs.contains(cp))
                    .forEach(cp -> missing.add(new String(Character.toChars(cp))
                            + " (U+" + Integer.toHexString(cp) + ") in \"" + text + "\""));
        }
        assertTrue(missing.isEmpty(), "Missing glyphs:\n" + String.join("\n", missing));
    }

    @Test
    void theFontCoversAllOfLatin1() throws IOException {
        Set<Integer> glyphs = fontGlyphs();
        for (int cp = 0xC0; cp <= 0xFF; cp++) {
            assertTrue(glyphs.contains(cp), "U+" + Integer.toHexString(cp));
        }
    }
}
