package com.paddleshock.data;

/** One entry in the local friends list (see {@link PlayerProfile#getFriends()}) - a player id the
 *  local player pasted in themselves plus a nickname they assigned. Entirely local, not mutual and
 *  not synced anywhere - adding someone here never notifies them. Plain Gson-serializable data, no
 *  behavior beyond a couple of display helpers, same style as {@link RivalRecord}. */
public final class Friend {

    private String playerId;
    private String nickname;
    private long dateAdded;

    public Friend(String playerId, String nickname, long dateAdded) {
        this.playerId = playerId;
        this.nickname = nickname;
        this.dateAdded = dateAdded;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getNickname() {
        return nickname;
    }

    public long getDateAdded() {
        return dateAdded;
    }

    /** Shortened stand-in for {@link #playerId}, same convention as
     *  {@code RankClient.LeaderboardEntry#shortId()}/{@code RivalRecord#displayName()}: "Player-"
     *  plus the first 8 characters of the id with dashes stripped. */
    public String shortId() {
        String id = playerId == null ? "" : playerId.replace("-", "");
        return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
    }
}
