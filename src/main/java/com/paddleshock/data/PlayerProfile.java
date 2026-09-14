package com.paddleshock.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persisted player state: currency, owned items, current loadout, and ranked identity. */
public class PlayerProfile {

    private static final int POWERUP_SLOTS = 3;

    /** Local match history is capped at this many most-recent entries (oldest dropped past it) -
     *  see {@link #addMatchHistoryEntry(MatchHistoryEntry)}. */
    private static final int MATCH_HISTORY_CAP = 50;

    /** Bump when PlayerProfile's schema changes in a way that needs migration. */
    public static final int CURRENT_VERSION = 1;

    // Defaults to 0 (not CURRENT_VERSION) so Gson leaves it at 0 for pre-versioning saves that
    // predate this field entirely, letting migrateIfNeeded() tell "old save, never versioned"
    // apart from "save explicitly at version N".
    private int saveVersion = CURRENT_VERSION;

    private int currency = 300;

    // Identifies this player to the ranked ladder backend (see aws/README.md) - a random id
    // generated once on first use, not tied to any account/login. Old saves predate this field
    // and deserialize it as null; getPlayerId() generates and persists one lazily in that case.
    private String playerId;

    // First-launch onboarding: false until the player has viewed the HOW TO PLAY screen once
    // (either automatically on first launch, or by opening it from the main menu) - see
    // HowToPlayState/PaddleShockApp#showHowToPlay. Old saves predate this field and deserialize it
    // as false too, so an existing player sees it once as well - a one-time no-op inconvenience,
    // not worth a separate "is this actually a brand-new save" check.
    private boolean hasSeenTutorial = false;

    // Local, editable display name shown on the PROFILE screen when Steam isn't available (see
    // SteamManager#getPersonaName). Old saves predate this field and deserialize it as null;
    // getDisplayName() falls back to a sensible default in that case, same treatment as
    // hasSeenTutorial above - not worth a migration step for a plain string default.
    private String displayName = "Player";

    // Most-recent-first local match history, capped at MATCH_HISTORY_CAP - see
    // addMatchHistoryEntry(). Old saves predate this field and deserialize it as null;
    // getMatchHistory() treats that the same as an empty list.
    private List<MatchHistoryEntry> matchHistory = new ArrayList<>();

    // Local head-to-head tracker, keyed by opponent playerId - see recordRivalResult(). Old saves
    // predate this field and deserialize it as null; getRivals()/recordRivalResult() treat that
    // the same as an empty map. A Map (not a List, matching matchHistory's convention) since
    // lookup/update by opponent id is the primary access pattern here, unlike match history's
    // append-only most-recent-first list.
    private Map<String, RivalRecord> rivals = new HashMap<>();

    // Local friends list, keyed by playerId - see addFriend()/removeFriend()/getFriends(). Old
    // saves predate this field and deserialize it as null; the same accessors below treat that as
    // an empty map, same no-migration-needed treatment as rivals above. Entirely local: adding
    // someone here doesn't notify them, isn't mutual, and is never synced to any backend - only
    // the small "invite a friend to my lobby" mailbox (see aws/README.md "Direct invites") talks
    // to AWS at all, and only once the local player explicitly sends an invite.
    private Map<String, Friend> friends = new HashMap<>();

    // Seasonal peak-rank reward (see aws/README.md "Seasonal peak-rank reward"): the last season
    // number this profile was actually paid out for, so the reward is granted at most once per
    // season. Defaults to -1 (not 0, which is itself a valid season number) so any real season
    // number the server returns counts as "new". Old saves predate this field and deserialize it
    // via this same initializer (never touched by Gson, same no-migration-needed treatment as
    // hasSeenTutorial/displayName above), so an old save's first ever getRank response is always
    // treated as unclaimed.
    private int lastRewardedSeason = -1;

    private Set<String> ownedPaddleIds = new HashSet<>(Set.of("paddle_classic"));
    private Set<String> ownedTableIds = new HashSet<>(Set.of("table_classic"));
    private Set<String> ownedBallIds = new HashSet<>(Set.of("ball_classic"));
    private Set<String> ownedPowerUpIds = new HashSet<>();

    private String equippedPaddleId = "paddle_classic";
    private String equippedTableId = "table_classic";
    private String equippedBallId = "ball_classic";
    private String equippedLevelId = "level_classic";

    /** Up to 3 owned power-up ids, one per key slot (1/2/3); a slot is empty when null. */
    private List<String> powerUpLoadout = new ArrayList<>(List.of("", "", ""));

    public int getSaveVersion() {
        return saveVersion;
    }

    /**
     * Brings a freshly-deserialized profile up to {@link #CURRENT_VERSION}, applying any
     * migration steps needed along the way. Safe to call on a profile that's already current
     * (no-op). A save with saveVersion 0 predates this field entirely (old save, never
     * versioned) and is treated as version 1 for migration purposes since no schema changes
     * have happened since.
     *
     * A save reporting a version NEWER than this build knows about (saveVersion > CURRENT_VERSION)
     * is a downgrade scenario - an older build opening a newer save. There's nothing to roll back
     * here, so we log a warning and proceed best-effort with the fields we understand, leaving
     * saveVersion as-is rather than silently stamping it down to CURRENT_VERSION.
     */
    public void migrateIfNeeded() {
        int fromVersion = saveVersion == 0 ? 1 : saveVersion;

        if (fromVersion > CURRENT_VERSION) {
            System.err.println("PlayerProfile save is version " + fromVersion
                    + ", newer than this build's CURRENT_VERSION (" + CURRENT_VERSION
                    + "). Proceeding best-effort; some fields may be ignored.");
            return;
        }

        // Migration steps go here as the schema evolves, e.g.:
        // if (fromVersion < 2) { ... upgrade v1 -> v2 fields ...; fromVersion = 2; }

        saveVersion = CURRENT_VERSION;
    }

    public int getCurrency() {
        return currency;
    }

    /** This player's identity for the ranked ladder backend - generated once, lazily, and kept
     *  stable across saves thereafter. Callers should {@code SaveManager.saveProfile()} shortly
     *  after the first call that actually generates one, or it'll regenerate on next launch. */
    public String getPlayerId() {
        if (playerId == null) {
            playerId = UUID.randomUUID().toString();
        }
        return playerId;
    }

    public boolean hasSeenTutorial() {
        return hasSeenTutorial;
    }

    public void setHasSeenTutorial(boolean hasSeenTutorial) {
        this.hasSeenTutorial = hasSeenTutorial;
    }

    public void addCurrency(int amount) {
        currency += amount;
    }

    /** The local display name; falls back to "Player" for a fresh/old save with none set, or if
     *  it was somehow cleared to blank. The PROFILE screen only shows/edits this when Steam is
     *  unavailable - see {@code SteamManager#getPersonaName()}. */
    public String getDisplayName() {
        return (displayName == null || displayName.isBlank()) ? "Player" : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = (displayName == null || displayName.isBlank()) ? "Player" : displayName.trim();
    }

    /** Most-recent-first local match history, capped at {@link #MATCH_HISTORY_CAP} entries. */
    public List<MatchHistoryEntry> getMatchHistory() {
        return matchHistory == null ? List.of() : List.copyOf(matchHistory);
    }

    /** Records a completed match at the front of the history, dropping the oldest entry past
     *  {@link #MATCH_HISTORY_CAP}. */
    public void addMatchHistoryEntry(MatchHistoryEntry entry) {
        if (matchHistory == null) {
            matchHistory = new ArrayList<>();
        }
        matchHistory.add(0, entry);
        while (matchHistory.size() > MATCH_HISTORY_CAP) {
            matchHistory.remove(matchHistory.size() - 1);
        }
    }

    /** The season number the seasonal peak-rank reward was last paid out for; -1 if never claimed. */
    public int getLastRewardedSeason() {
        return lastRewardedSeason;
    }

    public void setLastRewardedSeason(int season) {
        this.lastRewardedSeason = season;
    }

    /** Local head-to-head records against other players, most-played-first - see
     *  {@link #recordRivalResult}. Never {@code null}. */
    public List<RivalRecord> getRivals() {
        if (rivals == null || rivals.isEmpty()) {
            return List.of();
        }
        List<RivalRecord> sorted = new ArrayList<>(rivals.values());
        sorted.sort(Comparator.comparingInt(RivalRecord::getGamesPlayed).reversed());
        return sorted;
    }

    /** Creates or updates the local head-to-head record against {@code opponentPlayerId}, called
     *  alongside match-history recording for any match where the real opponent's playerId is
     *  known (ranked or unranked, LAN or internet - see the match-end paths in
     *  {@code PaddleShockApp}). {@code opponentDisplayNameHint} may be {@code null}/blank if no
     *  better hint than the id itself is available; a non-blank hint always overwrites a stale one
     *  so a later match can improve on an earlier fallback. No-ops if {@code opponentPlayerId} is
     *  {@code null}/blank (no real opponent to attribute this to). */
    public void recordRivalResult(String opponentPlayerId, String opponentDisplayNameHint, boolean won) {
        if (opponentPlayerId == null || opponentPlayerId.isBlank()) {
            return;
        }
        if (rivals == null) {
            rivals = new HashMap<>();
        }
        RivalRecord record = rivals.computeIfAbsent(opponentPlayerId,
                id -> new RivalRecord(id, opponentDisplayNameHint));
        if (opponentDisplayNameHint != null && !opponentDisplayNameHint.isBlank()) {
            record.setOpponentDisplayNameHint(opponentDisplayNameHint);
        }
        record.recordResult(won, System.currentTimeMillis());
    }

    /** The local friends list, most-recently-added-first. Never {@code null}. */
    public List<Friend> getFriends() {
        if (friends == null || friends.isEmpty()) {
            return List.of();
        }
        List<Friend> sorted = new ArrayList<>(friends.values());
        sorted.sort(Comparator.comparingLong(Friend::getDateAdded).reversed());
        return sorted;
    }

    /** Adds (or, if already a friend, renames) {@code playerId} to the local friends list under
     *  {@code nickname}. No-ops if {@code playerId} is {@code null}/blank. Not mutual - the other
     *  player is never notified. */
    public void addFriend(String playerId, String nickname) {
        if (playerId == null || playerId.isBlank()) {
            return;
        }
        if (friends == null) {
            friends = new HashMap<>();
        }
        String finalNickname = (nickname == null || nickname.isBlank()) ? playerId : nickname.trim();
        Friend existing = friends.get(playerId);
        long dateAdded = existing != null ? existing.getDateAdded() : System.currentTimeMillis();
        friends.put(playerId, new Friend(playerId, finalNickname, dateAdded));
    }

    /** Removes {@code playerId} from the local friends list, if present. */
    public void removeFriend(String playerId) {
        if (friends != null) {
            friends.remove(playerId);
        }
    }

    public boolean owns(String category, String id) {
        // Levels are free for everyone, regardless of save history - never gated like paddle/table/ball.
        if ("level".equals(category)) {
            return true;
        }
        return ownedSetFor(category).contains(id);
    }

    /** Attempts to buy the item; deducts currency and marks it owned on success. */
    public boolean purchase(String category, String id, int price) {
        if (owns(category, id) || currency < price) {
            return false;
        }
        currency -= price;
        ownedSetFor(category).add(id);
        return true;
    }

    public void equip(String category, String id) {
        if (!owns(category, id)) {
            return;
        }
        switch (category) {
            case "paddle" -> equippedPaddleId = id;
            case "table" -> equippedTableId = id;
            case "ball" -> equippedBallId = id;
            case "level" -> equippedLevelId = id;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        }
    }

    public String getEquippedId(String category) {
        return switch (category) {
            case "paddle" -> equippedPaddleId;
            case "table" -> equippedTableId;
            case "ball" -> equippedBallId;
            case "level" -> equippedLevelId;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    /** Not called for "level" - owns() always returns true for it before reaching here. */
    private Set<String> ownedSetFor(String category) {
        return switch (category) {
            case "paddle" -> ownedPaddleIds;
            case "table" -> ownedTableIds;
            case "ball" -> ownedBallIds;
            default -> throw new IllegalArgumentException("Unknown category: " + category);
        };
    }

    public boolean ownsPowerUp(String id) {
        return ownedPowerUpIds.contains(id);
    }

    /** Attempts to buy the power-up unlock; deducts currency and marks it owned on success. */
    public boolean purchasePowerUp(String id, int price) {
        if (ownsPowerUp(id) || currency < price) {
            return false;
        }
        currency -= price;
        ownedPowerUpIds.add(id);
        return true;
    }

    /** The 3 loadout slots (key 1/2/3); an empty string means that slot has nothing assigned. */
    public List<String> getLoadout() {
        normalizeLoadoutSize();
        return List.copyOf(powerUpLoadout);
    }

    public String getLoadoutSlot(int slot) {
        normalizeLoadoutSize();
        return powerUpLoadout.get(slot);
    }

    /**
     * Assigns an owned power-up to a slot (or clears it with {@code null}/""), bumping it out of
     * any other slot it already occupied so the same power-up never fills two slots at once.
     */
    public void setLoadoutSlot(int slot, String powerUpId) {
        normalizeLoadoutSize();
        String id = powerUpId == null ? "" : powerUpId;
        if (!id.isEmpty() && !ownsPowerUp(id)) {
            return;
        }
        for (int i = 0; i < powerUpLoadout.size(); i++) {
            if (!id.isEmpty() && id.equals(powerUpLoadout.get(i))) {
                powerUpLoadout.set(i, "");
            }
        }
        powerUpLoadout.set(slot, id);
    }

    /** Old saves (or a save made before power-ups existed) may have fewer than 3 slots recorded. */
    private void normalizeLoadoutSize() {
        if (powerUpLoadout == null) {
            powerUpLoadout = new ArrayList<>();
        }
        while (powerUpLoadout.size() < POWERUP_SLOTS) {
            powerUpLoadout.add("");
        }
    }
}
