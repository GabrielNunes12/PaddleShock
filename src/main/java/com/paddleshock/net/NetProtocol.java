package com.paddleshock.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

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
     *       0 = the host chose UNRANKED in {@code MultiplayerState}). The host's choice is
     *       authoritative - this is how the joiner learns which match-end path to use
     *       ({@code endMatch} vs {@code endRankedJoinerMatch}). A payload-less WELCOME (older
     *       host, or malformed) decodes as ranked, matching the original always-ranked behavior.</li>
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

    /** Max UDP payload we ever send; comfortably above the largest (snapshot) message. */
    public static final int MAX_PACKET_SIZE = 256;

    public static byte[] encodeHandshake(byte type) {
        return new byte[] {type};
    }

    public static byte messageType(byte[] data) {
        return data.length == 0 ? 0 : data[0];
    }

    // ---- WELCOME (host -> joiner) ----

    public static byte[] encodeWelcome(boolean ranked) {
        return new byte[] {TYPE_WELCOME, (byte) (ranked ? 1 : 0)};
    }

    /** {@code true} for a ranked match, including a payload-less WELCOME (older host, or a
     *  malformed/truncated packet) - see the class doc for why that's the safe default. */
    public static boolean decodeWelcomeRanked(byte[] data) {
        return data.length <= 1 || data[1] != 0;
    }

    // ---- HELLO (joiner -> host) ----

    public static byte[] encodeHello(String playerId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_HELLO);
            out.writeUTF(playerId == null ? "" : playerId);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode hello packet", e);
        }
    }

    /** Returns the joiner's player id, or {@code ""} for a payload-less packet (a Phase-D punch
     *  packet reusing this type byte, or an older client) - never throws for that case, only for
     *  a genuinely truncated/corrupt UTF payload. */
    public static String decodeHello(byte[] data) throws IOException {
        if (data.length <= 1) {
            return "";
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        in.readByte(); // type
        return in.readUTF();
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

    public record SnapshotMessage(
            float ballX, float ballY, float ballZ,
            float ballVelX, float ballVelZ, float ballVerticalVel,
            float hostPaddleX, float hostPaddleZ,
            float joinerPaddleX, float joinerPaddleZ,
            int hostScore, int joinerScore,
            int flags,
            int powerUpActorSide, int powerUpTypeOrdinal) {

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
        return new SnapshotMessage(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel,
                hostPaddleX, hostPaddleZ, joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags,
                powerUpActorSide, powerUpTypeOrdinal);
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
