package com.paddleshock.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import com.paddleshock.data.Catalog;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.sim.PaddleInput;

/**
 * The listen-server side of a LAN match: binds a UDP port, accepts exactly one joiner, and is
 * driven once per frame from {@code GameplayAppState.update(tpf)} on the render thread. The ONLY
 * background thread here is the UDP receive loop, which does nothing but decode packets into a
 * thread-safe single-slot "latest input" holder - all jME scene-graph and {@code MatchSimulation}
 * work stays on the render thread.
 */
public class NetHost implements AutoCloseable {

    private final DatagramSocket socket;
    private final Thread receiveThread;
    private volatile boolean running = true;

    private final InetSocketAddress publicAddress;
    private volatile InetSocketAddress joinerAddress;
    private final AtomicReference<NetProtocol.InputMessage> latestInput =
            new AtomicReference<>(NetProtocol.InputMessage.NEUTRAL);

    public NetHost(int port) throws SocketException {
        socket = new DatagramSocket(port);
        // STUN discovery does its own blocking socket.receive() calls, so it must finish (and
        // its per-attempt SO_TIMEOUT must be restored) before the receive thread starts reading
        // the same socket - otherwise the two would race for incoming packets.
        publicAddress = StunClient.discoverPublicAddress(socket);
        receiveThread = new Thread(this::receiveLoop, "NetHost-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[NetProtocol.MAX_PACKET_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                continue; // socket closed on shutdown, or a transient receive error - just retry/exit
            }
            byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                    packet.getOffset() + packet.getLength());
            handlePacket(data, (InetSocketAddress) packet.getSocketAddress());
        }
    }

    private void handlePacket(byte[] data, InetSocketAddress from) {
        if (data.length == 0) {
            return;
        }
        try {
            switch (NetProtocol.messageType(data)) {
                case NetProtocol.TYPE_HELLO -> handleHello(from);
                case NetProtocol.TYPE_INPUT -> latestInput.set(NetProtocol.decodeInput(data));
                default -> {
                    // unknown/malformed - ignore
                }
            }
        } catch (IOException e) {
            // malformed packet - ignore, next one may be fine
        }
    }

    private void handleHello(InetSocketAddress from) {
        synchronized (this) {
            if (joinerAddress == null || joinerAddress.equals(from)) {
                joinerAddress = from;
                sendRaw(from, NetProtocol.encodeHandshake(NetProtocol.TYPE_WELCOME));
            } else {
                sendRaw(from, NetProtocol.encodeHandshake(NetProtocol.TYPE_REJECT));
            }
        }
    }

    private void sendRaw(InetSocketAddress to, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException e) {
            // best-effort; the joiner will simply retry hello, or the next snapshot will fail too
        }
    }

    /** Whether a joiner has completed the handshake yet. */
    public boolean hasJoiner() {
        return joinerAddress != null;
    }

    /** The most recently received joiner input, resolved into the same {@link PaddleInput} shape
     *  the AI/local player already feed into {@code MatchSimulation.tick}, or a neutral/no-op
     *  input if nothing has arrived yet. */
    public PaddleInput pollJoinerPaddleInput() {
        NetProtocol.InputMessage msg = latestInput.get();
        PowerUpDefinition activated = (msg.powerUpId() == null || msg.powerUpId().isEmpty())
                ? null
                : Catalog.findPowerUp(msg.powerUpId()).orElse(null);
        return new PaddleInput(msg.deltaX(), msg.deltaZ(), activated);
    }

    /** Sends this tick's authoritative snapshot to the joiner, if one is connected. */
    public void sendSnapshot(NetProtocol.SnapshotMessage snapshot) {
        InetSocketAddress to = joinerAddress;
        if (to == null) {
            return;
        }
        sendRaw(to, NetProtocol.encodeSnapshot(snapshot));
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    /** This machine's public ip:port as seen from the internet (via STUN), for internet play
     *  through AWS lobby-code matchmaking. {@code null} if discovery failed (no internet, or all
     *  STUN traffic was blocked) - callers should fall back to LAN-only direct connect. */
    public InetSocketAddress getPublicAddress() {
        return publicAddress;
    }

    /** Registers this host with the AWS lobby broker and returns a short code the joiner can
     *  enter instead of typing this machine's raw address. Blocking network call - run off the
     *  render thread. Throws if no public address was discovered (offline, or STUN blocked). */
    public String registerLobby() throws IOException {
        if (publicAddress == null) {
            throw new IOException("no public address available for internet play");
        }
        return LobbyClient.create(StunClient.format(publicAddress));
    }

    private static final long LOBBY_POLL_TIMEOUT_MS = 30_000;
    private static final long LOBBY_POLL_INTERVAL_MS = 1_500;
    private static final long PUNCH_WINDOW_MS = 8_000;
    private static final long PUNCH_INTERVAL_MS = 300;

    /**
     * Polls the AWS lobby broker (see {@code aws/README.md}) for {@code lobbyCode} until a
     * joiner's public address shows up, then actively sends toward it for a few seconds to punch
     * this machine's own NAT open - reacting to an inbound HELLO alone (as before Phase D) isn't
     * enough, since the joiner's very first HELLO packets may be dropped by THIS host's NAT
     * before it has ever sent anything outbound to the joiner's address. Both sides punching at
     * once is what actually opens a two-way path on most home NATs.
     *
     * <p>Long-running (up to ~{@link #LOBBY_POLL_TIMEOUT_MS} + {@link #PUNCH_WINDOW_MS}) and
     * blocking (HTTPS polling + timed sends) - run on its own background thread, not the render
     * thread. Returns early and does nothing further once {@link #hasJoiner()} becomes true
     * (e.g. a LAN joiner connected directly) or once {@link #close()} is called.
     */
    public void pollAndPunchUntilJoined(String lobbyCode) {
        try {
            pollAndPunchUntilJoinedInner(lobbyCode);
        } finally {
            lobbyAttemptFinished = true;
        }
    }

    private void pollAndPunchUntilJoinedInner(String lobbyCode) {
        String joinerAddressText = null;
        long pollDeadline = System.currentTimeMillis() + LOBBY_POLL_TIMEOUT_MS;
        while (running && !hasJoiner() && System.currentTimeMillis() < pollDeadline) {
            try {
                joinerAddressText = LobbyClient.poll(lobbyCode);
            } catch (IOException e) {
                joinerAddressText = null; // transient - keep retrying until the deadline
            }
            if (joinerAddressText != null) {
                break;
            }
            sleepQuietly(LOBBY_POLL_INTERVAL_MS);
        }

        if (!running || hasJoiner() || joinerAddressText == null) {
            return;
        }

        InetSocketAddress target;
        try {
            target = StunClient.parseAddress(joinerAddressText);
        } catch (IOException e) {
            return; // lobby returned something unparseable - give up quietly, LAN path still works
        }

        long punchDeadline = System.currentTimeMillis() + PUNCH_WINDOW_MS;
        while (running && !hasJoiner() && System.currentTimeMillis() < punchDeadline) {
            sendRaw(target, NetProtocol.encodeHandshake(NetProtocol.TYPE_HELLO));
            sleepQuietly(PUNCH_INTERVAL_MS);
        }
    }

    private volatile boolean lobbyAttemptFinished = false;

    /** True once {@link #pollAndPunchUntilJoined} has returned (successfully or not) - lets the
     *  UI tell "still trying the internet code" apart from "gave up, only LAN can work now". */
    public boolean isLobbyAttemptFinished() {
        return lobbyAttemptFinished;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
