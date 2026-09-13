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

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
