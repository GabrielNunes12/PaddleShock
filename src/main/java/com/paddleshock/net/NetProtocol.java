package com.paddleshock.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

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
 *   <li>{@link #TYPE_WELCOME} - host to joiner: connection accepted, just the type byte.</li>
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

    /** Max UDP payload we ever send; comfortably above the largest (snapshot) message. */
    public static final int MAX_PACKET_SIZE = 256;

    public static byte[] encodeHandshake(byte type) {
        return new byte[] {type};
    }

    public static byte messageType(byte[] data) {
        return data.length == 0 ? 0 : data[0];
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
            int flags) {

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
        return new SnapshotMessage(ballX, ballY, ballZ, ballVelX, ballVelZ, ballVerticalVel,
                hostPaddleX, hostPaddleZ, joinerPaddleX, joinerPaddleZ, hostScore, joinerScore, flags);
    }
}
