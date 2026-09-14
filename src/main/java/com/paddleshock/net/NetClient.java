package com.paddleshock.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.paddleshock.diagnostics.NetLog;

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
    /** This player's equipped power-up loadout (catalog ids, empty-string for an unfilled slot -
     *  see {@code PlayerProfile.getLoadout()}), sent with every HELLO so the host can validate a
     *  later activation against it rather than trusting a free-form id - see
     *  {@code NetHost#handleHello}/{@link NetProtocol#sanitizePowerUpId}. */
    private final List<String> loadout;
    /** True for a read-only spectator connection (see {@link NetProtocol.Role#SPECTATE}) - never
     *  sends input (see {@link #sendInput}) and is never treated by the host as "the opponent". A
     *  spectator's loadout is irrelevant, but its HELLO still carries its playerId for
     *  logging/consistency, same as a playing joiner's. */
    private final boolean spectator;
    private final String lobbyCode; // null for a direct IP:port connect - see connectByLobbyCode
    private volatile boolean connected = false;
    private volatile boolean rejected = false;
    // Defaults true (ranked) to match the game's original always-ranked behavior until an actual
    // WELCOME arrives - see NetProtocol.decodeWelcomeRanked.
    private volatile boolean ranked = true;
    /** The host's ranked-ladder player id, learned from WELCOME (see {@code NetHost#localPlayerId}
     *  and {@link NetProtocol#decodeWelcomeHostPlayerId}) - {@code ""} until connected, or for an
     *  older host that didn't send one. Used by the local rival tracker. */
    private volatile String hostPlayerId = "";
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

    public NetClient(String hostAddress, int port, String localPlayerId, List<String> loadout) throws IOException {
        this(hostAddress, port, localPlayerId, loadout, false);
    }

    /** {@code spectator} - see {@link #spectator}. */
    public NetClient(String hostAddress, int port, String localPlayerId, List<String> loadout, boolean spectator)
            throws IOException {
        this.hostAddress = new InetSocketAddress(InetAddress.getByName(hostAddress), port);
        this.localPlayerId = localPlayerId;
        this.loadout = loadout == null ? List.of() : List.copyOf(loadout);
        this.spectator = spectator;
        this.lobbyCode = null;
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
            String localPlayerId, List<String> loadout, String lobbyCode, boolean spectator) {
        this.socket = socket;
        this.publicAddress = publicAddress;
        this.hostAddress = hostAddress;
        this.localPlayerId = localPlayerId;
        this.loadout = loadout == null ? List.of() : List.copyOf(loadout);
        this.spectator = spectator;
        this.lobbyCode = lobbyCode;
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
    public static NetClient connectByLobbyCode(String code, String localPlayerId, List<String> loadout)
            throws IOException {
        return connectByLobbyCode(code, localPlayerId, loadout, false);
    }

    /** {@code spectator} - see {@link #spectator}. */
    public static NetClient connectByLobbyCode(String code, String localPlayerId, List<String> loadout,
            boolean spectator) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        InetSocketAddress publicAddress = StunClient.discoverPublicAddress(socket);
        if (publicAddress == null) {
            socket.close();
            throw new IOException("no public address available for internet play (offline, or STUN is blocked)");
        }
        // Only the internet/lobby-code path needs gating here - direct LAN IP:port connects never
        // touch STUN at all, so a symmetric NAT on this machine is irrelevant to them. Best-effort:
        // detectsSymmetricNat never throws and is bounded to ~1-2s.
        if (StunClient.detectsSymmetricNat(socket)) {
            socket.close();
            throw new IOException(StunClient.SYMMETRIC_NAT_MESSAGE);
        }
        String hostAddressText;
        try {
            hostAddressText = LobbyClient.join(code, StunClient.format(publicAddress), localPlayerId);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        InetSocketAddress hostAddress = StunClient.parseAddress(hostAddressText);
        return new NetClient(socket, publicAddress, hostAddress, localPlayerId, loadout, code, spectator);
    }

    /** Re-sends the handshake "hello"; safe to call repeatedly while waiting for a welcome
     *  (e.g. from a UI poll loop) since the host treats a repeat hello from the same peer as
     *  a no-op re-accept rather than a second connection. Carries this player's ranked-ladder id
     *  (see {@code RankClient}) so the host can report a ranked match's result for both players.
     *  A spectator's loadout is irrelevant (its HELLO is sent with {@code role=SPECTATE} - see
     *  {@link NetProtocol.Role}), but its playerId is still carried, for logging/consistency. */
    public void sendHello() {
        sendRaw(NetProtocol.encodeHello(localPlayerId, loadout,
                spectator ? NetProtocol.Role.SPECTATE : NetProtocol.Role.PLAY));
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
                    ranked = NetProtocol.decodeWelcomeRanked(data);
                    hostPlayerId = NetProtocol.decodeWelcomeHostPlayerId(data);
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
                case NetProtocol.TYPE_KEEPALIVE -> lastHostPacketAt = System.currentTimeMillis();
                default -> {
                    // unknown/malformed - ignore
                }
            }
        } catch (IOException e) {
            // malformed packet - ignore
            NetLog.log("NetClient received a malformed packet from the host", e);
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

    /** Whether the host chose to play this match ranked - only meaningful once {@link #isConnected()};
     *  defaults to {@code true} beforehand. */
    public boolean isRanked() {
        return ranked;
    }

    /** The host's ranked-ladder player id (see {@code RankClient}), or {@code ""} before
     *  {@link #isConnected()} or if the host didn't send one (an older host). Used by the local
     *  rival tracker to record a result against this opponent. */
    public String getHostPlayerId() {
        return hostPlayerId;
    }

    /** Sends this frame's local input to the host. {@code powerUpId} is the catalog id of a
     *  power-up activated this frame, or an empty string if none. A no-op for a spectator
     *  connection - defensive belt-and-suspenders on top of {@code GameplayAppState} simply never
     *  calling this in {@code Mode.SPECTATOR}, so a spectator can never influence the match no
     *  matter what calls this. */
    public void sendInput(float deltaX, float deltaZ, String powerUpId) {
        if (spectator) {
            return;
        }
        sendRaw(NetProtocol.encodeInput(deltaX, deltaZ, powerUpId));
    }

    /** True for a read-only spectator connection - see {@link #spectator}. */
    public boolean isSpectator() {
        return spectator;
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

    /** See {@link NetProtocol#TYPE_KEEPALIVE} - {@code MatchEndState} calls this periodically so
     *  {@link #isHostTimedOut()} doesn't fire just because the match ended and nothing else is
     *  being sent while the result screen is up. */
    public void sendKeepAlive() {
        sendRaw(NetProtocol.encodeHandshake(NetProtocol.TYPE_KEEPALIVE));
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

    /** The AWS lobby code this client joined through (see {@link #connectByLobbyCode}), or
     *  {@code null} for a direct IP:port LAN connect. The joiner never reports match results
     *  itself (the host does, for both players - see {@code aws/README.md} "Trust model"), so
     *  this is kept mainly for symmetry/diagnostics with {@code NetHost.getLobbyCode()}. */
    public String getLobbyCode() {
        return lobbyCode;
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }
}
