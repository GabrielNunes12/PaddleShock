package com.paddleshock.net;

import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip encode/decode coverage for every {@link NetProtocol} message shape, plus the
 *  documented "truncated/corrupt payload" failure contracts. */
class NetProtocolTest {

    // ---- HELLO ----

    @Test
    void helloRoundTripsPlayerIdAndLoadout() throws IOException {
        byte[] packet = NetProtocol.encodeHello("player-123", List.of("powerup_speed_boost", "", "powerup_paddle_grow"));

        assertEquals(NetProtocol.TYPE_HELLO, NetProtocol.messageType(packet));
        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(packet);
        assertEquals("player-123", decoded.playerId());
        assertEquals(List.of("powerup_speed_boost", "", "powerup_paddle_grow"), decoded.loadout());
    }

    @Test
    void helloEncodesNullPlayerIdAsEmptyStringAndNullLoadoutAsEmptyList() throws IOException {
        byte[] packet = NetProtocol.encodeHello(null, null);

        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(packet);
        assertEquals("", decoded.playerId());
        assertEquals(List.of(), decoded.loadout());
    }

    @Test
    void decodeHelloReturnsEmptyForPayloadLessPacket() throws IOException {
        // A Phase-D host-to-joiner punch packet reuses TYPE_HELLO with no payload at all.
        byte[] punchPacket = NetProtocol.encodeHandshake(NetProtocol.TYPE_HELLO);

        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(punchPacket);
        assertEquals("", decoded.playerId());
        assertEquals(List.of(), decoded.loadout());
    }

    @Test
    void helloDefaultsToPlayRoleWhenEncodedWithoutOne() throws IOException {
        // The original two-arg encodeHello (still used by every existing PLAY-role caller).
        byte[] packet = NetProtocol.encodeHello("player-123", List.of());

        assertEquals(NetProtocol.Role.PLAY, NetProtocol.decodeHello(packet).role());
    }

    @Test
    void helloRoundTripsSpectateRole() throws IOException {
        byte[] packet = NetProtocol.encodeHello("spectator-1", List.of(), NetProtocol.Role.SPECTATE);

        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(packet);
        assertEquals("spectator-1", decoded.playerId());
        assertEquals(NetProtocol.Role.SPECTATE, decoded.role());
    }

    @Test
    void helloRoundTripsPlayRoleViaThreeArgOverload() throws IOException {
        byte[] packet = NetProtocol.encodeHello("player-123", List.of("powerup_speed_boost"), NetProtocol.Role.PLAY);

        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(packet);
        assertEquals(NetProtocol.Role.PLAY, decoded.role());
        assertEquals(List.of("powerup_speed_boost"), decoded.loadout());
    }

    @Test
    void decodeHelloDefaultsToPlayRoleForAnOlderHelloWithNoTrailingRoleByte() throws IOException {
        // Simulates an older build's HELLO: playerId + loadout count/entries, but no role byte at
        // all - must still decode (as PLAY) rather than throwing, same graceful degradation the
        // loadout field itself already gets for an even older HELLO with no loadout at all.
        byte[] fullPacket = NetProtocol.encodeHello("player-123", List.of("powerup_speed_boost"), NetProtocol.Role.SPECTATE);
        byte[] withoutRoleByte = new byte[fullPacket.length - 1];
        System.arraycopy(fullPacket, 0, withoutRoleByte, 0, withoutRoleByte.length);

        NetProtocol.HelloMessage decoded = NetProtocol.decodeHello(withoutRoleByte);
        assertEquals(NetProtocol.Role.PLAY, decoded.role());
        assertEquals(List.of("powerup_speed_boost"), decoded.loadout());
    }

    @Test
    void decodeHelloReturnsPlayRoleForPayloadLessPacket() throws IOException {
        byte[] punchPacket = NetProtocol.encodeHandshake(NetProtocol.TYPE_HELLO);

        assertEquals(NetProtocol.Role.PLAY, NetProtocol.decodeHello(punchPacket).role());
    }

    @Test
    void decodeHelloThrowsOnTruncatedUtfPayload() {
        byte[] packet = NetProtocol.encodeHello("player-123", List.of());
        // Keep the type byte and the UTF length prefix, but chop off the actual string bytes.
        byte[] truncated = new byte[3];
        System.arraycopy(packet, 0, truncated, 0, truncated.length);

        assertThrows(EOFException.class, () -> NetProtocol.decodeHello(truncated));
    }

    // ---- power-up ownership validation (sanitizePowerUpId) ----

    @Test
    void sanitizePowerUpIdReturnsRequestedIdWhenAllowed() {
        Set<String> allowed = Set.of("powerup_speed_boost", "powerup_paddle_grow");

        assertEquals("powerup_speed_boost", NetProtocol.sanitizePowerUpId("powerup_speed_boost", allowed));
    }

    @Test
    void sanitizePowerUpIdDropsIdNotInAllowedSet() {
        // e.g. a modified client claiming a power-up it never declared/owns.
        Set<String> allowed = Set.of("powerup_speed_boost");

        assertEquals("", NetProtocol.sanitizePowerUpId("powerup_tiny_paddle", allowed));
    }

    @Test
    void sanitizePowerUpIdDropsEmptyOrNullRequest() {
        Set<String> allowed = Set.of("powerup_speed_boost");

        assertEquals("", NetProtocol.sanitizePowerUpId("", allowed));
        assertEquals("", NetProtocol.sanitizePowerUpId(null, allowed));
    }

    @Test
    void sanitizePowerUpIdDropsEverythingWhenAllowedSetIsEmptyOrNull() {
        assertEquals("", NetProtocol.sanitizePowerUpId("powerup_speed_boost", Set.of()));
        assertEquals("", NetProtocol.sanitizePowerUpId("powerup_speed_boost", null));
    }

    // ---- WELCOME / REJECT (payload-less handshake messages) ----

    @Test
    void welcomeAndRejectAreSingleTypeByteMessages() {
        byte[] welcome = NetProtocol.encodeHandshake(NetProtocol.TYPE_WELCOME);
        byte[] reject = NetProtocol.encodeHandshake(NetProtocol.TYPE_REJECT);

        assertEquals(1, welcome.length);
        assertEquals(NetProtocol.TYPE_WELCOME, NetProtocol.messageType(welcome));
        assertEquals(1, reject.length);
        assertEquals(NetProtocol.TYPE_REJECT, NetProtocol.messageType(reject));
    }

    @Test
    void messageTypeOfEmptyPacketIsZero() {
        assertEquals(0, NetProtocol.messageType(new byte[0]));
    }

    // ---- INPUT ----

    @Test
    void inputRoundTripsAllFields() throws IOException {
        byte[] packet = NetProtocol.encodeInput(0.25f, -0.75f, "speed_boost_1");

        assertEquals(NetProtocol.TYPE_INPUT, NetProtocol.messageType(packet));
        NetProtocol.InputMessage decoded = NetProtocol.decodeInput(packet);
        assertEquals(0.25f, decoded.deltaX());
        assertEquals(-0.75f, decoded.deltaZ());
        assertEquals("speed_boost_1", decoded.powerUpId());
    }

    @Test
    void inputEncodesNullPowerUpIdAsEmptyString() throws IOException {
        byte[] packet = NetProtocol.encodeInput(1f, 2f, null);

        NetProtocol.InputMessage decoded = NetProtocol.decodeInput(packet);
        assertEquals("", decoded.powerUpId());
    }

    @Test
    void inputNeutralConstantHasZeroDeltasAndNoPowerUp() {
        assertEquals(0f, NetProtocol.InputMessage.NEUTRAL.deltaX());
        assertEquals(0f, NetProtocol.InputMessage.NEUTRAL.deltaZ());
        assertEquals("", NetProtocol.InputMessage.NEUTRAL.powerUpId());
    }

    @Test
    void decodeInputThrowsOnTruncatedPacket() {
        byte[] packet = NetProtocol.encodeInput(1f, 2f, "x");
        byte[] truncated = new byte[5]; // type byte + only 4 of the 8 deltaX/deltaZ float bytes
        System.arraycopy(packet, 0, truncated, 0, truncated.length);

        assertThrows(EOFException.class, () -> NetProtocol.decodeInput(truncated));
    }

    // ---- SNAPSHOT ----

    @Test
    void snapshotRoundTripsAllFieldsAndFlags() throws IOException {
        int flags = NetProtocol.FLAG_WALL_BOUNCE
                | NetProtocol.FLAG_HOST_PADDLE_HIT
                | NetProtocol.FLAG_MATCH_OVER
                | NetProtocol.FLAG_HOST_WON;
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1.5f, 0.35f, -2.25f,
                3.0f, -4.0f, 1.1f,
                0.5f, -6.5f,
                -0.5f, 6.5f,
                7, 9,
                flags,
                NetProtocol.SnapshotMessage.ACTOR_HOST, 2);

        byte[] packet = NetProtocol.encodeSnapshot(original);
        assertEquals(NetProtocol.TYPE_SNAPSHOT, NetProtocol.messageType(packet));

        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(packet);
        assertEquals(original.ballX(), decoded.ballX());
        assertEquals(original.ballY(), decoded.ballY());
        assertEquals(original.ballZ(), decoded.ballZ());
        assertEquals(original.ballVelX(), decoded.ballVelX());
        assertEquals(original.ballVelZ(), decoded.ballVelZ());
        assertEquals(original.ballVerticalVel(), decoded.ballVerticalVel());
        assertEquals(original.hostPaddleX(), decoded.hostPaddleX());
        assertEquals(original.hostPaddleZ(), decoded.hostPaddleZ());
        assertEquals(original.joinerPaddleX(), decoded.joinerPaddleX());
        assertEquals(original.joinerPaddleZ(), decoded.joinerPaddleZ());
        assertEquals(original.hostScore(), decoded.hostScore());
        assertEquals(original.joinerScore(), decoded.joinerScore());
        assertEquals(original.flags(), decoded.flags());
        assertEquals(original.powerUpActorSide(), decoded.powerUpActorSide());
        assertEquals(original.powerUpTypeOrdinal(), decoded.powerUpTypeOrdinal());

        assertTrue(decoded.isWallBounce());
        assertTrue(decoded.isHostPaddleHit());
        assertFalse(decoded.isJoinerPaddleHit());
        assertFalse(decoded.isPowerUpActivated());
        assertTrue(decoded.isMatchOver());
        assertTrue(decoded.isHostWon());
    }

    @Test
    void snapshotWithNoFlagsRoundTripsAllFalse() throws IOException {
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0, 0,
                NetProtocol.SnapshotMessage.ACTOR_NONE, 0);

        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(NetProtocol.encodeSnapshot(original));

        assertFalse(decoded.isWallBounce());
        assertFalse(decoded.isHostPaddleHit());
        assertFalse(decoded.isJoinerPaddleHit());
        assertFalse(decoded.isPowerUpActivated());
        assertFalse(decoded.isMatchOver());
        assertFalse(decoded.isHostWon());
    }

    @Test
    void decodeSnapshotThrowsOnTruncatedPacket() {
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11, 12, 0,
                NetProtocol.SnapshotMessage.ACTOR_NONE, 0);
        byte[] packet = NetProtocol.encodeSnapshot(original);
        byte[] truncated = new byte[10]; // well short of the full fixed-width snapshot payload

        assertThrows(EOFException.class, () -> NetProtocol.decodeSnapshot(truncated));
    }

    @Test
    void snapshotRoundTripsSpin() throws IOException {
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 3, 4, 0,
                NetProtocol.SnapshotMessage.ACTOR_NONE, -1, -1.25f);
        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(NetProtocol.encodeSnapshot(original));
        assertEquals(original, decoded);
    }

    @Test
    void snapshotFromAnOlderHostWithoutSpinDecodesAsNoSpin() throws IOException {
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 3, 4, 0,
                NetProtocol.SnapshotMessage.ACTOR_HOST, 1, 1.9f);
        byte[] full = NetProtocol.encodeSnapshot(original);
        byte[] legacy = java.util.Arrays.copyOf(full, full.length - 4);
        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(legacy);
        assertEquals(0f, decoded.ballSpin());
        assertEquals(NetProtocol.SnapshotMessage.ACTOR_HOST, decoded.powerUpActorSide());
        assertEquals(1, decoded.powerUpTypeOrdinal());
        assertEquals(10f, decoded.joinerPaddleZ());
    }

    @Test
    void snapshotRoundTripsEffectsAndShieldFlag() throws IOException {
        int effects = NetProtocol.EFFECT_HOST_SHIELD | NetProtocol.EFFECT_JOINER_GHOSTED | NetProtocol.EFFECT_JOINER_CURVEBALL;
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 3, 4, NetProtocol.FLAG_SHIELD_BLOCK,
                NetProtocol.SnapshotMessage.ACTOR_NONE, -1, 0.5f, effects);
        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(NetProtocol.encodeSnapshot(original));
        assertEquals(original, decoded);
        assertTrue(decoded.isShieldBlock());
        assertTrue(decoded.hasEffect(NetProtocol.EFFECT_HOST_SHIELD));
        assertFalse(decoded.hasEffect(NetProtocol.EFFECT_JOINER_SHIELD));
    }

    @Test
    void snapshotFromAHostWithSpinButNoEffectsDecodesAsNoEffects() throws IOException {
        NetProtocol.SnapshotMessage original = new NetProtocol.SnapshotMessage(
                1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 3, 4, 0,
                NetProtocol.SnapshotMessage.ACTOR_NONE, -1, 1.5f, NetProtocol.EFFECT_HOST_SHIELD);
        byte[] full = NetProtocol.encodeSnapshot(original);
        NetProtocol.SnapshotMessage decoded = NetProtocol.decodeSnapshot(java.util.Arrays.copyOf(full, full.length - 1));
        assertEquals(0, decoded.effects());
        assertEquals(1.5f, decoded.ballSpin());
    }
}
