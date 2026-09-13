package com.paddleshock.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The joiner side of a LAN match: connects to a host address:port, sends its own local input
 * once per frame, and applies whatever authoritative snapshot most recently arrived directly to
 * the local render state. Runs no {@code MatchSimulation} of its own - it is a pure renderer of
 * received state plus a sender of local input. Like {@link NetHost}, the only background thread
 * is the UDP receive loop; everything else is driven once per frame from the render thread.
 */
public class NetClient implements AutoCloseable {

    private final DatagramSocket socket;
    private final InetSocketAddress hostAddress;
    private final Thread receiveThread;
    private volatile boolean running = true;

    private final InetSocketAddress publicAddress;
    private final String localPlayerId;
    private volatile boolean connected = false;
    private volatile boolean rejected = false;
    private final AtomicReference<NetProtocol.SnapshotMessage> latestSnapshot = new AtomicReference<>();
    private final AtomicReference<NetProtocol.RankResultMessage> relayedRankResult = new AtomicReference<>();

    /** Wall-clock time (millis) the last packet of any kind arrived from the host - the basis for
     *  {@link #isHostTimedOut()}. A {@code TYPE_SNAPSHOT} arrives every host tick once connected,
     *  so plain silence past a few seconds means the host's process died or the network dropped;
     *  there's no need for a dedicated heartbeat message type. */
    private volatile long lastHostPacketAt = 0L;

    /** How long without any packet from the host before it's considered disconnected. */
    public static final long DISCONNECT_TIMEOUT_MS = 5_000;

    private volatile boolean rematchRequestedByHost = false;
    private volatile boolean rematchAcceptedByHost = false;
    private volatile boolean rematchDeclinedByHost = false;

    public NetClient(String hostAddress, int port, String localPlayerId) throws IOException {
        this.hostAddress = new InetSocketAddress(InetAddress.getByName(hostAddress), port);
        this.localPlayerId = localPlayerId;
        socket = new DatagramSocket();
        // Same ordering constraint as NetHost: STUN discovery's own blocking receives must
        // finish before the background receive thread starts reading this socket.
        publicAddress = StunClient.discoverPublicAddress(socket);
        receiveThread = new Thread(this::receiveLoop, "NetClient-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
        sendHello();
    }

    /** Internal constructor used by {@link #connectByLobbyCode}, where the public address is
     *  already known (discovered before the host's address was) and doesn't need rediscovering. */
    private NetClient(DatagramSocket socket, InetSocketAddress publicAddress, InetSocketAddress hostAddress,
            String localPlayerId) {
        this.socket = socket;
        this.publicAddress = publicAddress;
        this.hostAddress = hostAddress;
        this.localPlayerId = localPlayerId;
        receiveThread = new Thread(this::receiveLoop, "NetClient-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
        sendHello();
    }

    /**
     * Connects via an AWS lobby code instead of a typed IP:port: binds a socket, discovers this
     * machine's public address on THAT socket (must happen first - the registered address has to
     * match the socket that will actually carry game traffic), registers as the joiner for
     * {@code code} to learn the host's public address, then connects to it exactly like the
     * direct constructor. Blocking network calls (STUN + the lobby HTTPS round trip) - run off
     * the render thread.
     */
    public static NetClient connectByLobbyCode(String code, String localPlayerId) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        InetSocketAddress publicAddress = StunClient.discoverPublicAddress(socket);
        if (publicAddress == null) {
            socket.close();
            throw new IOException("no public address available for internet play (offline, or STUN is blocked)");
        }
        String hostAddressText;
        try {
            hostAddressText = LobbyClient.join(code, StunClient.format(publicAddress));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        InetSocketAddress hostAddress = StunClient.parseAddress(hostAddressText);
        return new NetClient(socket, publicAddress, hostAddress, localPlayerId);
    }

    /** Re-sends the handshake "hello"; safe to call repeatedly while waiting for a welcome
     *  (e.g. from a UI poll loop) since the host treats a repeat hello from the same peer as
     *  a no-op re-accept rather than a second connection. Carries this player's ranked-ladder id
     *  (see {@code RankClient}) so the host can report a ranked match's result for both players. */
    public void sendHello() {
        sendRaw(NetProtocol.encodeHello(localPlayerId));
    }

    private void receiveLoop() {
        byte[] buffer = new byte[NetProtocol.MAX_PACKET_SIZE];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                continue; // socket closed on shutdown, or a transient receive error
            }
            byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                    packet.getOffset() + packet.getLength());
            handlePacket(data);
        }
    }

    private void handlePacket(byte[] data) {
        if (data.length == 0) {
            return;
        }
        try {
            switch (NetProtocol.messageType(data)) {
                case NetProtocol.TYPE_WELCOME -> {
                    connected = true;
                    lastHostPacketAt = System.currentTimeMillis();
                }
                case NetProtocol.TYPE_REJECT -> rejected = true;
                case NetProtocol.TYPE_SNAPSHOT -> {
                    latestSnapshot.set(NetProtocol.decodeSnapshot(data));
                    lastHostPacketAt = System.currentTimeMillis();
                }
                case NetProtocol.TYPE_REMATCH_REQUEST -> {
                    rematchRequestedByHost = true;
                    lastHostPacketAt = System.currentTimeMillis();
                }
                case NetProtocol.TYPE_REMATCH_ACCEPT -> {
                    rematchAcceptedByHost = true;
                    lastHostPacketAt = System.currentTimeMillis();
                }
                case NetProtocol.TYPE_REMATCH_DECLINE -> {
                    rematchDeclinedByHost = true;
                    lastHostPacketAt = System.currentTimeMillis();
                }
                case NetProtocol.TYPE_RANK_RESULT -> {
                    relayedRankResult.set(NetProtocol.decodeRankResult(data));
                    lastHostPacketAt = System.currentTimeMillis();
                }
                default -> {
                    // unknown/malformed - ignore
                }
            }
        } catch (IOException e) {
            // malformed packet - ignore
        }
    }

    private void sendRaw(byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, hostAddress));
        } catch (IOException e) {
            // best-effort; next frame's send (or hello retry) may succeed
        }
    }

    /** True once the host has replied WELCOME. */
    public boolean isConnected() {
        return connected;
    }

    /** True once the host has replied REJECT (already has a peer). */
    public boolean isRejected() {
        return rejected;
    }

    /** Sends this frame's local input to the host. {@code powerUpId} is the catalog id of a
     *  power-up activated this frame, or an empty string if none. */
    public void sendInput(float deltaX, float deltaZ, String powerUpId) {
        sendRaw(NetProtocol.encodeInput(deltaX, deltaZ, powerUpId));
    }

    /** The most recently received authoritative snapshot, or {@code null} if none has arrived yet. */
    public NetProtocol.SnapshotMessage getLatestSnapshot() {
        return latestSnapshot.get();
    }

    /** True once the host has gone silent for {@link #DISCONNECT_TIMEOUT_MS} after having
     *  connected - its process died, or the network dropped. False before a welcome ever arrived
     *  (that's just "still connecting", not a disconnect). */
    public boolean isHostTimedOut() {
        return connected && lastHostPacketAt > 0
                && System.currentTimeMillis() - lastHostPacketAt > DISCONNECT_TIMEOUT_MS;
    }

    /** Proposes a rematch to the host. */
    public void sendRematchRequest() {
        sendRaw(NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_REQUEST));
    }

    public void sendRematchAccept() {
        sendRaw(NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_ACCEPT));
    }

    public void sendRematchDecline() {
        sendRaw(NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_DECLINE));
    }

    public boolean isRematchRequestedByPeer() {
        return rematchRequestedByHost;
    }

    public boolean isRematchAccepted() {
        return rematchAcceptedByHost;
    }

    public boolean isRematchDeclined() {
        return rematchDeclinedByHost;
    }

    /** Clears all rematch flags - see {@code NetHost#resetRematchState} for why. */
    public void resetRematchState() {
        rematchRequestedByHost = false;
        rematchAcceptedByHost = false;
        rematchDeclinedByHost = false;
    }

    /** Consumes (returns and clears) the host's relayed authoritative post-match rank result, or
     *  {@code null} if none has arrived (yet, or ever - e.g. the host's own report call failed, or
     *  the connection dropped right after match-end). Consumed exactly once so a caller polling
     *  this every frame doesn't re-process the same relay repeatedly. */
    public NetProtocol.RankResultMessage pollRelayedRankResult() {
        return relayedRankResult.getAndSet(null);
    }

    /** This machine's public ip:port as seen from the internet (via STUN), for internet play
     *  through AWS lobby-code matchmaking. {@code null} if discovery failed (no internet, or all
     *  STUN traffic was blocked) - callers should fall back to LAN-only direct connect. */
    public InetSocketAddress getPublicAddress() {
        return publicAddress;
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
