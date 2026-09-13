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
    private volatile boolean connected = false;
    private volatile boolean rejected = false;
    private final AtomicReference<NetProtocol.SnapshotMessage> latestSnapshot = new AtomicReference<>();

    public NetClient(String hostAddress, int port) throws IOException {
        this.hostAddress = new InetSocketAddress(InetAddress.getByName(hostAddress), port);
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
    private NetClient(DatagramSocket socket, InetSocketAddress publicAddress, InetSocketAddress hostAddress) {
        this.socket = socket;
        this.publicAddress = publicAddress;
        this.hostAddress = hostAddress;
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
    public static NetClient connectByLobbyCode(String code) throws IOException {
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
        InetSocketAddress hostAddress = parseAddress(hostAddressText);
        return new NetClient(socket, publicAddress, hostAddress);
    }

    private static InetSocketAddress parseAddress(String text) throws IOException {
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new IOException("lobby service returned a malformed address: " + text);
        }
        try {
            InetAddress host = InetAddress.getByName(text.substring(0, colon));
            int port = Integer.parseInt(text.substring(colon + 1));
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException e) {
            throw new IOException("lobby service returned a malformed address: " + text);
        }
    }

    /** Re-sends the handshake "hello"; safe to call repeatedly while waiting for a welcome
     *  (e.g. from a UI poll loop) since the host treats a repeat hello from the same peer as
     *  a no-op re-accept rather than a second connection. */
    public void sendHello() {
        sendRaw(NetProtocol.encodeHandshake(NetProtocol.TYPE_HELLO));
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
                case NetProtocol.TYPE_WELCOME -> connected = true;
                case NetProtocol.TYPE_REJECT -> rejected = true;
                case NetProtocol.TYPE_SNAPSHOT -> latestSnapshot.set(NetProtocol.decodeSnapshot(data));
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
