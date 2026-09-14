package com.paddleshock.net;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.paddleshock.sim.PaddleInput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-socket (loopback) coverage for two of {@link NetHost}'s host-authoritative behaviors that
 * are awkward to unit test in isolation since they live behind a private {@code handleHello} on a
 * background receive thread: (1) a joiner's claimed power-up activation is validated against the
 * loadout it declared at HELLO time, and (2) a HELLO carrying an already-connected joiner's player
 * id, but arriving from a DIFFERENT address, is welcomed back as a reconnect rather than rejected.
 *
 * <p>Runs on 127.0.0.1 with OS-assigned ports - no GUI, no jME, no external network. Every wait
 * below is a short bounded poll rather than a blocking receive, so a regression that breaks the
 * handshake fails the assertion instead of hanging the test run.
 */
class NetHostIntegrationTest {

    private static final long POLL_TIMEOUT_MS = 2_000;
    private static final long POLL_INTERVAL_MS = 20;

    private NetHost host;
    private NetClient client;
    private DatagramSocket rawSocket;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (host != null) {
            host.close();
        }
        if (rawSocket != null) {
            rawSocket.close();
        }
    }

    @Test
    void hostOnlyHonorsAPowerUpTheJoinerDeclaredInItsHello() throws Exception {
        host = new NetHost(0, "host-player", true);
        client = new NetClient("127.0.0.1", host.getPort(), "joiner-player",
                List.of("powerup_speed_boost", "", ""));

        awaitTrue(client::isConnected, "client never received WELCOME");
        awaitTrue(host::hasJoiner, "host never recorded the joiner");

        // Declared/owned power-up: the host should honor it.
        client.sendInput(0f, 0f, "powerup_speed_boost");
        awaitTrue(() -> host.pollJoinerPaddleInput().getActivatedPowerUp() != null,
                "host never honored the declared power-up");
        assertEquals("powerup_speed_boost", host.pollJoinerPaddleInput().getActivatedPowerUp().getId());

        // Undeclared power-up (e.g. a modified client claiming one it never told the host about,
        // or never actually owns) - the host must silently drop the activation, not honor it.
        client.sendInput(0f, 0f, "powerup_tiny_paddle");
        // Give the packet a moment to actually land over the loopback socket, then assert it stays
        // rejected across a few polls rather than racing a single read right after send().
        for (int i = 0; i < 5; i++) {
            Thread.sleep(POLL_INTERVAL_MS);
            PaddleInput input = host.pollJoinerPaddleInput();
            assertNull(input.getActivatedPowerUp(),
                    "host must not honor a power-up the joiner never declared in its HELLO");
        }
    }

    @Test
    void hostWelcomesBackAReconnectFromANewAddressWithTheSamePlayerId() throws Exception {
        host = new NetHost(0, "host-player", true);
        client = new NetClient("127.0.0.1", host.getPort(), "joiner-player", List.of());
        awaitTrue(client::isConnected, "client never received WELCOME");
        awaitTrue(host::hasJoiner, "host never recorded the joiner");

        // Simulate the joiner's NAT remapping mid-match: a fresh socket (different local port,
        // standing in for "a different external address") sends a HELLO carrying the SAME player
        // id the host already has on file for its current joiner.
        rawSocket = new DatagramSocket();
        rawSocket.setSoTimeout((int) POLL_TIMEOUT_MS);
        byte[] reconnectHello = NetProtocol.encodeHello("joiner-player", List.of());
        InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), host.getPort());
        rawSocket.send(new DatagramPacket(reconnectHello, reconnectHello.length, hostAddress));

        byte[] buffer = new byte[NetProtocol.MAX_PACKET_SIZE];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        rawSocket.receive(reply); // bounded by the SO_TIMEOUT set above - won't hang the suite
        byte[] replyData = java.util.Arrays.copyOfRange(reply.getData(), reply.getOffset(),
                reply.getOffset() + reply.getLength());
        assertEquals(NetProtocol.TYPE_WELCOME, NetProtocol.messageType(replyData),
                "host must accept (WELCOME) a reconnect HELLO with a matching player id from a new address");

        // The host should now trust input arriving from the NEW address too.
        byte[] inputFromNewAddress = NetProtocol.encodeInput(0.4f, -0.2f, "");
        rawSocket.send(new DatagramPacket(inputFromNewAddress, inputFromNewAddress.length, hostAddress));
        awaitTrue(() -> host.pollJoinerPaddleInput().getDeltaX() == 0.4f,
                "host never accepted input from the reconnected address");
    }

    @Test
    void hostRejectsAHelloFromAGenuinelyDifferentPlayerWhileAJoinerIsAlreadyConnected() throws Exception {
        host = new NetHost(0, "host-player", true);
        client = new NetClient("127.0.0.1", host.getPort(), "joiner-player", List.of());
        awaitTrue(client::isConnected, "client never received WELCOME");
        awaitTrue(host::hasJoiner, "host never recorded the joiner");

        rawSocket = new DatagramSocket();
        rawSocket.setSoTimeout((int) POLL_TIMEOUT_MS);
        byte[] strangerHello = NetProtocol.encodeHello("someone-else-entirely", List.of());
        InetSocketAddress hostAddress = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), host.getPort());
        rawSocket.send(new DatagramPacket(strangerHello, strangerHello.length, hostAddress));

        byte[] buffer = new byte[NetProtocol.MAX_PACKET_SIZE];
        DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
        rawSocket.receive(reply);
        byte[] replyData = java.util.Arrays.copyOfRange(reply.getData(), reply.getOffset(),
                reply.getOffset() + reply.getLength());
        assertEquals(NetProtocol.TYPE_REJECT, NetProtocol.messageType(replyData),
                "a genuinely different, non-matching player id must still be rejected while a joiner is connected");
    }

    private interface Condition {
        boolean isTrue() throws Exception;
    }

    private static void awaitTrue(Condition condition, String failureMessage) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.isTrue()) {
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        assertTrue(false, failureMessage);
    }
}
