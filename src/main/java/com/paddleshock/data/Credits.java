package com.paddleshock.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Everything shown on the in-game CREDITS screen ({@code ui.CreditsState}) - third-party assets
 * and libraries shipped in the build, with the attribution their licenses ask for. The per-folder
 * {@code CREDITS.md} files under {@code src/main/resources} carry the full source links; this is
 * the player-facing summary of the same information.
 *
 * <p>Names, titles and license names are proper nouns and are not translated - only the section
 * headings are ({@code credits.*} i18n keys).
 *
 * <p>{@code CreditsTest} fails if a model under {@code Models/} is in neither
 * {@link #THIRD_PARTY_MODELS} nor {@link #ORIGINAL_MODELS}, so a newly added CC-BY model can't
 * ship without its attribution.
 */
public final class Credits {

    private Credits() {
    }

    /** One CC-BY model, keyed by its path under {@code src/main/resources/Models/}. */
    public record ModelCredit(String assetPath, String title, String author) {
    }

    /** One displayed line; {@code dim} lines are license/source notes under an entry. */
    public record Line(String text, boolean dim) {
        static Line main(String text) {
            return new Line(text, false);
        }

        static Line note(String text) {
            return new Line(text, true);
        }
    }

    /** A titled block of lines; {@code headingKey} is an i18n key. */
    public record Section(String headingKey, List<Line> lines) {
    }

    /** All CC-BY models, via Poly Pizza (see {@code Models/CREDITS.md} for per-model links). */
    public static final List<ModelCredit> THIRD_PARTY_MODELS = List.of(
            new ModelCredit("Paddle/paddle.glb", "Table Tennis Paddle", "jeremy"),
            new ModelCredit("Decor/pingpong.glb", "Ping Pong table", "burunduk"),
            new ModelCredit("Decor/trophy.glb", "Trophy", "jeremy"),
            new ModelCredit("Ball/classic.glb", "Tennis ball", "Poly by Google"),
            new ModelCredit("Ball/beach.glb", "beach ball", "the_normalgamer"),
            new ModelCredit("Decor/bollard.glb", "Bollard", "J-Toastie"),
            new ModelCredit("Decor/arcade_machine.glb", "Arcade Machine", "J-Toastie"),
            new ModelCredit("Decor/palm_tree.glb", "Palm tree", "Poly by Google"),
            new ModelCredit("Decor/beach_umbrella.glb", "Beach umbrella", "Poly by Google"),
            new ModelCredit("Decor/satellite_dish.glb", "Satellite dish", "Poly by Google"));

    /** Models made for PaddleShock itself - no third-party attribution needed. */
    public static final Set<String> ORIGINAL_MODELS = Set.of(
            "Ball/pellet.glb",
            "Decor/bench.glb",
            "Decor/scoreboard.glb",
            "Decor/scoreboard_display.glb",
            "Paddle/paddle_turbo.glb",
            "Paddle/paddle_wall.glb");

    /** Left column: the 3D model attributions. */
    public static Section modelsSection() {
        List<Line> lines = new ArrayList<>();
        lines.add(Line.note("CC-BY, via Poly Pizza (poly.pizza)"));
        for (ModelCredit model : THIRD_PARTY_MODELS) {
            lines.add(Line.main(model.title() + " by " + model.author()));
        }
        return new Section("credits.models", lines);
    }

    /** Right column: audio, textures, font, and code libraries. */
    public static List<Section> otherSections() {
        return List.of(
                new Section("credits.music", List.of(
                        Line.main("8-bit Music Pack (Loopable) by CodeManu"),
                        Line.note("CC BY 3.0, via OpenGameArt.org"))),
                new Section("credits.sound_effects", List.of(
                        Line.main("Impact Sounds, Interface Sounds, Music Jingles by Kenney"),
                        Line.note("CC0, kenney.nl"))),
                new Section("credits.textures", List.of(
                        Line.main("Marble012, Plastic013A, Rubber004, Metal032, Concrete034, Asphalt012"),
                        Line.note("CC0, ambientCG.com"))),
                new Section("credits.font", List.of(
                        Line.main("Anton by The Anton Project Authors"),
                        Line.note("SIL Open Font License 1.1"))),
                new Section("credits.libraries", List.of(
                        Line.main("jMonkeyEngine, LWJGL, Lemur"),
                        Line.note("BSD 3-Clause License"),
                        Line.main("Gson, Guava, Groovy, Error Prone annotations"),
                        Line.note("Apache License 2.0"),
                        Line.main("steamworks4j, SLF4J"),
                        Line.note("MIT License"),
                        Line.main("J-Ogg by Tor-Einar Jarnbjo"),
                        Line.note("Free use with attribution"))));
    }
}
