package com.paddleshock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Keeps {@link Credits} in sync with what actually ships - a CC-BY model added to
 *  {@code Models/} without an attribution entry must fail the build, not reach players. */
class CreditsTest {

    private static final Path MODELS_DIR = Path.of("src/main/resources/Models");

    private static Set<String> shippedModels() throws IOException {
        try (Stream<Path> files = Files.walk(MODELS_DIR)) {
            return files.filter(p -> p.toString().endsWith(".glb"))
                    .map(p -> MODELS_DIR.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> thirdPartyPaths() {
        return Credits.THIRD_PARTY_MODELS.stream().map(Credits.ModelCredit::assetPath)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyShippedModelIsCreditedOrMarkedOriginal() throws IOException {
        Set<String> uncredited = new TreeSet<>(shippedModels());
        uncredited.removeAll(thirdPartyPaths());
        uncredited.removeAll(Credits.ORIGINAL_MODELS);
        assertTrue(uncredited.isEmpty(), "Models with no Credits entry: " + uncredited);
    }

    @Test
    void everyCreditedModelStillExists() throws IOException {
        Set<String> stale = new TreeSet<>(thirdPartyPaths());
        stale.addAll(Credits.ORIGINAL_MODELS);
        stale.removeAll(shippedModels());
        assertTrue(stale.isEmpty(), "Credits entries for models that no longer exist: " + stale);
    }

    @Test
    void noModelIsBothThirdPartyAndOriginal() {
        Set<String> overlap = new TreeSet<>(thirdPartyPaths());
        overlap.retainAll(Credits.ORIGINAL_MODELS);
        assertTrue(overlap.isEmpty(), "Listed as both third-party and original: " + overlap);
        assertEquals(Credits.THIRD_PARTY_MODELS.size(), thirdPartyPaths().size(), "Duplicate model entry");
    }

    @Test
    void everySectionHeadingIsTranslated() throws IOException {
        List<Credits.Section> sections = new java.util.ArrayList<>(Credits.otherSections());
        sections.add(Credits.modelsSection());
        for (String file : List.of("strings_en.properties", "strings_pt_BR.properties")) {
            Properties strings = load("/i18n/" + file);
            for (Credits.Section section : sections) {
                assertTrue(strings.containsKey(section.headingKey()), file + " missing " + section.headingKey());
                assertFalse(section.lines().isEmpty(), section.headingKey() + " has no lines");
            }
            for (String key : List.of("credits.title", "credits.back", "menu.credits")) {
                assertTrue(strings.containsKey(key), file + " missing " + key);
            }
        }
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = CreditsTest.class.getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
