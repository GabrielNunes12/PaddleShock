package com.paddleshock.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.paddleshock.powerups.PowerUpType;

/**
 * The Phase-1 LAN wire protocol: a minimal hand-rolled binary format over plain UDP
 * ({@link java.net.DatagramSocket}/{@link java.net.DatagramPacket}). No delivery/ordering
 * guarantees are needed - each side only ever cares about the MOST RECENT input/snapshot,
 * so a dropped or reordered packet is simply superseded by the next one.
 *
 * <p>Message shapes:
 * <ul>
 *   <li>{@link #TYPE_HELLO} - joiner to host, connection handshake: the type byte plus the
 *       joiner's ranked-ladder player id (see {@code RankClient}), so the host can report a
 *       ranked match's result for both players once it ends. A host's own outbound Phase-D punch
 *       packets reuse this same type byte with no payload at all ({@link #encodeHandshake}) -
 *       harmless, since those only ever travel host-to-joiner and the joiner already ignores any
 *       inbound {@code TYPE_HELLO} as unrecognized (it only reacts to WELCOME/REJECT/SNAPSHOT).</li>
 *   <li>{@link #TYPE_WELCOME} - host to joiner: connection accepted, the type byte plus a single "ranked" byte (1 = ranked ladder match,
     *       0 = the host chose UNRANKED in {@code MultiplayerState}), plus the host's own
     *       ranked-ladder player id (mirroring how HELLO already carries the joiner's), so BOTH
     *       sides of a match know each other's id - needed by the local rival tracker (see
     *       {@code PlayerProfile#recordRivalResult}). The host's ranked choice is authoritative -
     *       this is how the joiner learns which match-end path to use ({@code endMatch} vs
     *       {@code endRankedJoinerMatch}). A payload-less WELCOME (older host, or malformed)
     *       decodes as ranked with an empty host player id, matching the original always-ranked
     *       behavior and degrading gracefully (no rival entry recorded) rather than throwing.</li>
 *   <li>{@link #TYPE_REJECT} - host to joiner: already has a peer, just the type byte.</li>
 *   <li>{@link #TYPE_INPUT} - joiner to host, sent once per client frame: the joiner's own
 *       paddle deltaX/deltaZ for this frame, plus the catalog id of a power-up activated this
 *       frame (empty string if none). A catalog id (not a slot index) is sent because the host
 *       does not have a synced copy of the joiner's loadout in this phase - it just resolves the
 *       id straight back to a {@code PowerUpDefinition} via {@code Catalog.findPowerUp}.</li>
 *   <li>{@link #TYPE_SNAPSHOT} - host to joiner, sent once per host tick: authoritative ball
 *       position+velocity, both paddles' positions, both scores, and a flags byte carrying
 *       everything from {@code TickResult} the joiner needs to mirror SFX/HUD/match-over locally
 *       (it never runs its own {@code MatchSimulation}, so it cannot derive these itself).</li>
 * </ul>
 */
public final class NetProtocol {

    private NetProtocol() {
    }

    public static final byte TYPE_HELLO = 1;
    public static final byte TYPE_WELCOME = 2;
    public static final byte TYPE_REJECT = 3;
    public static final byte TYPE_INPUT = 4;
    public static final byte TYPE_SNAPSHOT = 5;
    /** Either side proposes replaying the same two players once a match ends - see
     *  {@code NetHost}/{@code NetClient} rematch methods and {@code MatchEndState}. Payload-less. */
    public static final byte TYPE_REMATCH_REQUEST = 6;
    /** The peer agrees to a proposed rematch. Payload-less. */
    public static final byte TYPE_REMATCH_ACCEPT = 7;
    /** The peer declines a proposed rematch. Payload-less. */
    public static final byte TYPE_REMATCH_DECLINE = 8;
    /** Host to joiner only, sent once after the host's {@code reportMatchResult} call succeeds:
     *  the joiner's own authoritative post-match {@code RankState} (as returned by the Lambda),
     *  so the joiner can show the real result instead of guessing via {@code withDeltaFrom}. */
    public static final byte TYPE_RANK_RESULT = 9;
    /** Either side, sent periodically while {@code MatchEndState} is showing a still-live
     *  multiplayer connection (before either player has clicked REMATCH). Keeps the passive
     *  "time since last packet" disconnect check from spuriously tripping just because a player
     *  spent a while reading the result screen without generating any real traffic - unlike
     *  during gameplay, nothing else is sent once the match is over. Payload-less. */
    public static final byte TYPE_KEEPALIVE = 10;

    /** Max UDP payload we ever send; comfortably above the largest (snapshot) message. */
    public static final int MAX_PACKET_SIZE = 256;

    public static byte[] encodeHandshake(byte type) {
        return new byte[] {type};
    }

    public static byte messageType(byte[] data) {
        return data.length == 0 ? 0 : data[0];
    }

    // ---- WELCOME (host -> joiner) ----

    public static byte[] encodeWelcome(boolean ranked, String hostPlayerId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_WELCOME);
            out.writeByte(ranked ? 1 : 0);
            out.writeUTF(hostPlayerId == null ? "" : hostPlayerId);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode welcome packet", e);
        }
    }

    /** {@code true} for a ranked match, including a payload-less WELCOME (older host, or a
     *  malformed/truncated packet) - see the class doc for why that's the safe default. */
    public static boolean decodeWelcomeRanked(byte[] data) {
        return data.length <= 1 || data[1] != 0;
    }

    /** The host's ranked-ladder player id (see {@code RankClient}), or {@code ""} for a WELCOME
     *  that doesn't carry one (an older host, or a truncated/malformed packet) - see the class
     *  doc for why that degrades gracefully rather than throwing. */
    public static String decodeWelcomeHostPlayerId(byte[] data) {
        if (data.length <= 2) {
            return "";
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            in.readByte(); // type
            in.readByte(); // ranked
            return in.readUTF();
        } catch (IOException e) {
            return "";
        }
    }

    // ---- HELLO (joiner -> host) ----

    /** The joiner's identity plus its currently-equipped power-up loadout (see
     *  {@code PlayerProfile.getLoadout()}), sent once at connect time (and re-sent on every retry/
     *  reconnect, so a mid-match loadout is never actually possible, but a reconnect always carries
     *  a fresh copy anyway) so the host can validate a later {@code TYPE_INPUT} activation against
     *  what this joiner is actually entitled to use rather than trusting a free-form catalog id -
     *  see {@code NetHost#handleHello} and {@link #sanitizePowerUpId}. {@code loadout} may contain
     *  empty-string entries for unfilled slots; callers should ignore those. */
    /** Which role a HELLO is connecting as. {@link #PLAY} is the original (and only, before
     *  spectator mode) role - a real participant who will send {@code TYPE_INPUT} and be treated
     *  as "the opponent" by {@code NetHost}. {@link #SPECTATE} is read-only: accepted into the
     *  host's separate spectator list regardless of whether a real joiner is already connected,
     *  receives the exact same broadcast snapshots, but is never treated as the joiner and never
     *  expected to send input. */
    public enum Role { PLAY, SPECTATE }

    /** Trailing role byte carried by a HELLO - appended after the loadout, following the same
     *  "older/newer peer degrades gracefully" pattern the loadout itself uses (see
     *  {@link #decodeHello}): a HELLO with no trailing role byte at all (an older build) decodes
     *  as {@link Role#PLAY}, matching the original always-a-player behavior. */
    public record HelloMessage(String playerId, List<String> loadout, Role role) {
        public static final HelloMessage EMPTY = new HelloMessage("", List.of(), Role.PLAY);
    }

    /** Encodes a {@link Role#PLAY} HELLO - the original, unchanged shape every existing host/joiner
     *  connect still uses. See {@link #encodeHello(String, List, Role)} for a spectator HELLO. */
    public static byte[] encodeHello(String playerId, List<String> loadout) {
        return encodeHello(playerId, loadout, Role.PLAY);
    }

    /** {@code role} carries the joiner's/spectator's playerId (spectators send it too, for
     *  logging/consistency, even though their loadout is irrelevant) plus which role this HELLO is
     *  connecting as - see {@link Role}. */
    public static byte[] encodeHello(String playerId, List<String> loadout, Role role) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_HELLO);
            out.writeUTF(playerId == null ? "" : playerId);
            List<String> safeLoadout = loadout == null ? List.of() : loadout;
            out.writeByte(safeLoadout.size());
            for (String id : safeLoadout) {
                out.writeUTF(id == null ? "" : id);
            }
            out.writeByte(role == Role.SPECTATE ? 1 : 0);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode hello packet", e);
        }
    }

    /** Returns {@link HelloMessage#EMPTY} for a payload-less packet (a Phase-D punch packet
     *  reusing this type byte, or an older client) - never throws for that case, only for a
     *  genuinely truncated/corrupt payload. An older HELLO carrying only a player id (no loadout
     *  count byte at all) decodes with an empty loadout rather than throwing, so a mismatched build
     *  still degrades gracefully instead of dropping every hello. Same degradation for the trailing
     *  role byte: missing entirely (an older client, or a hand-built loadout-only packet) decodes
     *  as {@link Role#PLAY}. */
    public static HelloMessage decodeHello(byte[] data) throws IOException {
        if (data.length <= 1) {
            return HelloMessage.EMPTY;
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readByte(); // type
        String playerId = in.readUTF();
        List<String> loadout = new ArrayList<>();
        if (in.available() > 0) {
            int count = in.readByte() & 0xFF;
            for (int i = 0; i < count; i++) {
                loadout.add(in.readUTF());
            }
        }
        Role role = Role.PLAY;
        if (in.available() > 0) {
            role = in.readByte() != 0 ? Role.SPECTATE : Role.PLAY;
        }
        return new HelloMessage(playerId, loadout, role);
    }

    /** Host-authoritative power-up ownership check: returns {@code requestedId} unchanged only if
     *  it's both non-empty and a member of {@code allowedIds} (the joiner's own declared loadout,
     *  intersected with the host's catalog - see {@code NetHost#handleHello}); otherwise returns
     *  {@code ""} so the caller treats it as "no activation this tick" rather than honoring a
     *  forged/free-form catalog id from a modified client. Pure and side-effect-free so it can be
     *  unit tested directly. */
    public static String sanitizePowerUpId(String requestedId, Set<String> allowedIds) {
        if (requestedId == null || requestedId.isEmpty()) {
            return "";
        }
        if (allowedIds == null || !allowedIds.contains(requestedId)) {
            return "";
        }
        return requestedId;
    }

    // ---- INPUT (joiner -> host) ----

    public record InputMessage(float deltaX, float deltaZ, String powerUpId) {
        public static final InputMessage NEUTRAL = new InputMessage(0f, 0f, "");
    }

    public static byte[] encodeInput(float deltaX, float deltaZ, String powerUpId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_INPUT);
            out.writeFloat(deltaX);
            out.writeFloat(deltaZ);
            out.writeUTF(powerUpId == null ? "" : powerUpId);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode input packet", e);
        }
    }

    public static InputMessage decodeInput(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readByte(); // type, already dispatched on by the caller
        float deltaX = in.readFloat();
        float deltaZ = in.readFloat();
        String powerUpId = in.readUTF();
        return new InputMessage(deltaX, deltaZ, powerUpId);
    }

    // ---- SNAPSHOT (host -> joiner) ----

    public static final int FLAG_WALL_BOUNCE = 1;
    public static final int FLAG_HOST_PADDLE_HIT = 1 << 1;
    public static final int FLAG_JOINER_PADDLE_HIT = 1 << 2;
    public static final int FLAG_POWERUP_ACTIVATED = 1 << 3;
    public static final int FLAG_MATCH_OVER = 1 << 4;
    public static final int FLAG_HOST_WON = 1 << 5;
    public static final int FLAG_SHIELD_BLOCK = 1 << 6;

    /** Bits of {@link SnapshotMessage#effects}: live behavior power-ups, host-perspective sides. */
    public static final int EFFECT_HOST_SHIELD = 1;
    public static final int EFFECT_JOINER_SHIELD = 1 << 1;
    /** Ghost Ball live on the host (the ball is hidden from the host mid-table). */
    public static final int EFFECT_HOST_GHOSTED = 1 << 2;
    public static final int EFFECT_JOINER_GHOSTED = 1 << 3;
    public static final int EFFECT_HOST_CURVEBALL = 1 << 4;
    public static final int EFFECT_JOINER_CURVEBALL = 1 << 5;

    public record SnapshotMessage(
            float ballX, float ballY, float ballZ,
            float ballVelX, float ballVelZ, float ballVerticalVel,
            float hostPaddleX, float hostPaddleZ,
            float joinerPaddleX, float joinerPaddleZ,
            int hostScore, int joinerScore,
            int flags,
            int powerUpActorSide, int powerUpTypeOrdinal,
            float ballSpin, int effects, float hazardOffset) {

        /** The pre-hazard field list; no arena hazard (bumper offset 0). */
        public SnapshotMessage(float ballX, float ballY, float ballZ, float ballVelX, float ballVelZ,
                float ballVerticalVel, float hostPaddleX, float hostPaddleZ, float joinerPaddleX,
                float joinerPaddleZ, int hostScore, int joinerScore, int flags, int powerUpActorSide,
                int powerUpTypeOrdinal, float ballSpin, int effects) {
            this(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel, hostPaddleX, hostPaddleZ,
                    joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags, powerUpActorSide,
                    powerUpTypeOrdinal, ballSpin, effects, 0f);
        }

        /** The pre-effects field list; no live behavior power-ups. */
        public SnapshotMessage(float ballX, float ballY, float ballZ, float ballVelX, float ballVelZ,
                float ballVerticalVel, float hostPaddleX, float hostPaddleZ, float joinerPaddleX,
                float joinerPaddleZ, int hostScore, int joinerScore, int flags, int powerUpActorSide,
                int powerUpTypeOrdinal, float ballSpin) {
            this(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel, hostPaddleX, hostPaddleZ,
                    joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags, powerUpActorSide,
                    powerUpTypeOrdinal, ballSpin, 0, 0f);
        }

        public boolean hasEffect(int effectBit) {
            return (effects & effectBit) != 0;
        }

        public boolean isShieldBlock() {
            return (flags & FLAG_SHIELD_BLOCK) != 0;
        }

        /** The pre-spin field list; spin defaults to 0. */
        public SnapshotMessage(float ballX, float ballY, float ballZ, float ballVelX, float ballVelZ,
                float ballVerticalVel, float hostPaddleX, float hostPaddleZ, float joinerPaddleX,
                float joinerPaddleZ, int hostScore, int joinerScore, int flags, int powerUpActorSide,
                int powerUpTypeOrdinal) {
            this(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel, hostPaddleX, hostPaddleZ,
                    joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags, powerUpActorSide,
                    powerUpTypeOrdinal, 0f, 0, 0f);
        }

        /** {@link #powerUpActorSide} values: who activated the power-up this tick (if any). */
        public static final int ACTOR_NONE = 0;
        public static final int ACTOR_HOST = 1;
        public static final int ACTOR_JOINER = 2;

        public boolean isWallBounce() {
            return (flags & FLAG_WALL_BOUNCE) != 0;
        }

        public boolean isHostPaddleHit() {
            return (flags & FLAG_HOST_PADDLE_HIT) != 0;
        }

        public boolean isJoinerPaddleHit() {
            return (flags & FLAG_JOINER_PADDLE_HIT) != 0;
        }

        public boolean isPowerUpActivated() {
            return (flags & FLAG_POWERUP_ACTIVATED) != 0;
        }

        /** Which power-up activated this tick (see {@link #isPowerUpActivated()}), or {@code null}
         *  if none did - lets the joiner's HUD name the actual effect (buff vs. debuff) instead of
         *  relying on the sound cue and its swatch color alone. */
        public PowerUpType getActivatedPowerUpType() {
            return powerUpTypeOrdinal < 0 ? null : PowerUpType.values()[powerUpTypeOrdinal];
        }

        public boolean isMatchOver() {
            return (flags & FLAG_MATCH_OVER) != 0;
        }

        public boolean isHostWon() {
            return (flags & FLAG_HOST_WON) != 0;
        }
    }

    public static byte[] encodeSnapshot(SnapshotMessage snap) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_SNAPSHOT);
            out.writeFloat(snap.ballX());
            out.writeFloat(snap.ballY());
            out.writeFloat(snap.ballZ());
            out.writeFloat(snap.ballVelX());
            out.writeFloat(snap.ballVelZ());
            out.writeFloat(snap.ballVerticalVel());
            out.writeFloat(snap.hostPaddleX());
            out.writeFloat(snap.hostPaddleZ());
            out.writeFloat(snap.joinerPaddleX());
            out.writeFloat(snap.joinerPaddleZ());
            out.writeInt(snap.hostScore());
            out.writeInt(snap.joinerScore());
            out.writeByte(snap.flags());
            out.writeByte(snap.powerUpActorSide());
            out.writeByte(snap.powerUpTypeOrdinal());
            out.writeFloat(snap.ballSpin());
            out.writeByte(snap.effects());
            out.writeFloat(snap.hazardOffset());
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode snapshot packet", e);
        }
    }

    public static SnapshotMessage decodeSnapshot(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readByte(); // type
        float ballX = in.readFloat();
        float ballY = in.readFloat();
        float ballZ = in.readFloat();
        float ballVelX = in.readFloat();
        float ballVelZ = in.readFloat();
        float ballVerticalVel = in.readFloat();
        float hostPaddleX = in.readFloat();
        float hostPaddleZ = in.readFloat();
        float joinerPaddleX = in.readFloat();
        float joinerPaddleZ = in.readFloat();
        int hostScore = in.readInt();
        int joinerScore = in.readInt();
        int flags = in.readByte() & 0xFF;
        // Older peers never sent these trailing bytes; default to "no power-up activator info"
        // instead of throwing, so a mismatched build still degrades gracefully (no banner/type,
        // same as before this field existed) rather than dropping every snapshot.
        int powerUpActorSide = SnapshotMessage.ACTOR_NONE;
        int powerUpTypeOrdinal = -1;
        if (in.available() >= 2) {
            powerUpActorSide = in.readByte();
            powerUpTypeOrdinal = in.readByte();
        }
        // Spin: likewise optional - an older host never sends it, which reads as no spin.
        float ballSpin = in.available() >= 4 ? in.readFloat() : 0f;
        int effects = in.available() >= 1 ? in.readByte() & 0xFF : 0;
        // The Pinball bumpers' offset - optional too (an older host has no hazards to report).
        float hazardOffset = in.available() >= 4 ? in.readFloat() : 0f;
        return new SnapshotMessage(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel,
                hostPaddleX, hostPaddleZ, joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags,
                powerUpActorSide, powerUpTypeOrdinal, ballSpin, effects, hazardOffset);
    }

    // ---- RANK_RESULT (host -> joiner) ----

    public record RankResultMessage(
            String tier, int division, int lp, int wins, int losses,
            int lpChange, boolean promoted, boolean demoted, String promoSeriesResult) {
    }

    public static byte[] encodeRankResult(String tier, int division, int lp, int wins, int losses,
            int lpChange, boolean promoted, boolean demoted, String promoSeriesResult) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_RANK_RESULT);
            out.writeUTF(tier);
            out.writeInt(division);
            out.writeInt(lp);
            out.writeInt(wins);
            out.writeInt(losses);
            out.writeInt(lpChange);
            out.writeBoolean(promoted);
            out.writeBoolean(demoted);
            out.writeUTF(promoSeriesResult == null ? "" : promoSeriesResult);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode rank result packet", e);
        }
    }

    public static RankResultMessage decodeRankResult(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readByte(); // type
        String tier = in.readUTF();
        int division = in.readInt();
        int lp = in.readInt();
        int wins = in.readInt();
        int losses = in.readInt();
        int lpChange = in.readInt();
        boolean promoted = in.readBoolean();
        boolean demoted = in.readBoolean();
        String promoSeriesResult = in.readUTF();
        return new RankResultMessage(tier, division, lp, wins, losses, lpChange, promoted, demoted, promoSeriesResult);
    }
}
