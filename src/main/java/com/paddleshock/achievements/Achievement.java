package com.paddleshock.achievements;

/**
 * Every achievement. The Steam API name ({@link #steamApiName()}) must match the one entered in
 * the Steamworks partner site exactly - see {@code docs/steam-achievements.md}. Titles and
 * descriptions are i18n keys {@code ach.<lowercase name>.title} / {@code .desc}.
 */
public enum Achievement {
    FIRST_WIN,
    SHUTOUT,
    CLOSE_CALL,
    WINS_25,
    WINS_100,
    BEAT_HARD_AI,
    ARENA_MASTER,
    POWER_PLAYER,
    TOUR_FIRST_BOSS,
    TOUR_COMPLETE,
    ONLINE_WIN,
    RANK_GOLD,
    RANK_DIAMOND,
    FULL_KIT,
    ALL_POWERUPS;

    public String steamApiName() {
        return "ACH_" + name();
    }

    public String titleKey() {
        return "ach." + name().toLowerCase() + ".title";
    }

    public String descKey() {
        return "ach." + name().toLowerCase() + ".desc";
    }
}
