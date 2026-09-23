package com.paddleshock.ui;

import com.paddleshock.challenges.Challenge;
import com.paddleshock.challenges.ChallengeKind;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.ItemDefinition;
import com.paddleshock.i18n.I18n;

/** Player-facing text for a {@link Challenge}. */
public final class ChallengeText {

    private ChallengeText() {
    }

    /** e.g. "Win 2 matches" / "Win a match on Neon Arcade" (a target of 1 uses the {@code .one} string). */
    public static String describe(Challenge challenge) {
        String key = keyFor(challenge);
        if (challenge.kind() == ChallengeKind.WINS_ON_ARENA) {
            String arena = Catalog.findLevel(challenge.levelId()).map(ItemDefinition::getDisplayName).orElse("?");
            return I18n.t(key, challenge.target(), arena);
        }
        return I18n.t(key, challenge.target());
    }

    /** The i18n key used for {@code challenge}'s description. */
    public static String keyFor(Challenge challenge) {
        return challenge.kind().descriptionKey() + (challenge.target() == 1 ? ".one" : "");
    }
}
