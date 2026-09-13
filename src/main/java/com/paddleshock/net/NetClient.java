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

    private volatile boolean connected = false;
    private volatile boolean rejected = false;
    private final AtomicReference<NetProtocol.SnapshotMessage> latestSnapshot = new AtomicReference<>();

    public NetClient(String hostAddress, int port) throws IOException {
        this.hostAddress = new InetSocketAddress(InetAddress.getByName(hostAddress), port);
        socket = new DatagramSocket();
        receiveThread = new Thread(this::receiveLoop, "NetClient-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();
        sendHello();
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

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
