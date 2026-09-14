package com.paddleshock.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    /** Best-effort symmetric-NAT diagnosis (see {@link StunClient#detectsSymmetricNat}) - only
     *  meaningful (and only computed) when {@link #publicAddress} was actually discovered, since a
     *  purely LAN-only host never needs STUN/NAT traversal at all. */
    private final boolean symmetricNatSuspected;
    private final String localPlayerId;
    private volatile InetSocketAddress joinerAddress;
    private volatile String joinerPlayerId = "";
    private volatile String lobbyCode;
    /** The catalog power-up ids the connected joiner is actually entitled to activate - the
     *  intersection of what it declared as its equipped loadout in its HELLO (see
     *  {@link NetProtocol.HelloMessage}) and this host's own catalog. Re-derived on every accepted
     *  HELLO (including a reconnect) so a stale entitlement never outlives the joiner that sent it.
     *  Never {@code null}; empty until a joiner has said hello. */
    private volatile Set<String> joinerAllowedPowerUpIds = Set.of();
    private final AtomicReference<NetProtocol.InputMessage> latestInput =
            new AtomicReference<>(NetProtocol.InputMessage.NEUTRAL);

    /** Wall-clock time (millis) the last packet of any kind arrived from the joiner - the basis
     *  for {@link #isJoinerTimedOut()}. {@code TYPE_INPUT} arrives every joiner frame once
     *  connected, so plain silence past a few seconds means the joiner's process died or the
     *  network dropped; there's no need for a dedicated heartbeat message type. */
    private volatile long lastJoinerPacketAt = 0L;

    /** How long without any packet from the joiner before it's considered disconnected. */
    public static final long DISCONNECT_TIMEOUT_MS = 5_000;

    private volatile boolean rematchRequestedByJoiner = false;
    private volatile boolean rematchAcceptedByJoiner = false;
    private volatile boolean rematchDeclinedByJoiner = false;

    /** Whether this host chose to play this match ranked - the host's own choice, made in
     *  {@code MultiplayerState} before hosting, and communicated to the joiner via the WELCOME
     *  handshake (see {@link NetProtocol}) since the host's choice is authoritative. */
    private final boolean ranked;

    public NetHost(int port, String localPlayerId, boolean ranked) throws SocketException {
        this.ranked = ranked;
        socket = new DatagramSocket(port);
        this.localPlayerId = localPlayerId;
        // STUN discovery does its own blocking socket.receive() calls, so it must finish (and
        // its per-attempt SO_TIMEOUT must be restored) before the receive thread starts reading
        // the same socket - otherwise the two would race for incoming packets.
        publicAddress = StunClient.discoverPublicAddress(socket);
        symmetricNatSuspected = publicAddress != null && StunClient.detectsSymmetricNat(socket);
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
                case NetProtocol.TYPE_HELLO -> handleHello(from, NetProtocol.decodeHello(data));
                case NetProtocol.TYPE_INPUT -> {
                    latestInput.set(NetProtocol.decodeInput(data));
                    noteJoinerPacket(from);
                }
                case NetProtocol.TYPE_REMATCH_REQUEST -> {
                    rematchRequestedByJoiner = true;
                    noteJoinerPacket(from);
                }
                case NetProtocol.TYPE_REMATCH_ACCEPT -> {
                    rematchAcceptedByJoiner = true;
                    noteJoinerPacket(from);
                }
                case NetProtocol.TYPE_REMATCH_DECLINE -> {
                    rematchDeclinedByJoiner = true;
                    noteJoinerPacket(from);
                }
                case NetProtocol.TYPE_KEEPALIVE -> noteJoinerPacket(from);
                default -> {
                    // unknown/malformed - ignore
                }
            }
        } catch (IOException e) {
            // malformed packet - ignore, next one may be fine
        }
    }

    /** Only counts packets from the joiner actually already on file (or handshaking in), matching
     *  the same peer-address check {@link #handleHello} already applies. */
    private void noteJoinerPacket(InetSocketAddress from) {
        if (joinerAddress != null && joinerAddress.equals(from)) {
            lastJoinerPacketAt = System.currentTimeMillis();
        }
    }

    private void handleHello(InetSocketAddress from, NetProtocol.HelloMessage hello) {
        synchronized (this) {
            String playerId = hello.playerId();
            boolean sameAddress = joinerAddress != null && joinerAddress.equals(from);
            // A reconnect after a NAT remap (Wi-Fi blip, mobile handoff, ...): the joiner is
            // already on file under a DIFFERENT address, but this HELLO carries the same
            // player id it originally connected with, so it's genuinely the same peer - accept it
            // and re-point where snapshots go / input is trusted from, rather than rejecting it as
            // "already has a peer" the way a HELLO from a truly different player would be. The
            // match itself (score, ball state) already lives in MatchSimulation on the render
            // thread, untouched by this - a reconnect just re-points the transport.
            boolean reconnectFromNewAddress = joinerAddress != null && !sameAddress
                    && !playerId.isEmpty() && playerId.equals(joinerPlayerId);
            if (joinerAddress == null || sameAddress || reconnectFromNewAddress) {
                joinerAddress = from;
                lastJoinerPacketAt = System.currentTimeMillis();
                if (!playerId.isEmpty()) {
                    joinerPlayerId = playerId;
                }
                joinerAllowedPowerUpIds = resolveAllowedPowerUpIds(hello.loadout());
                sendRaw(from, NetProtocol.encodeWelcome(ranked));
            } else {
                sendRaw(from, NetProtocol.encodeHandshake(NetProtocol.TYPE_REJECT));
            }
        }
    }

    /** Intersects the joiner's declared loadout (from its HELLO) with this host's own catalog, so
     *  a later {@code TYPE_INPUT} activation can be validated against what the joiner is actually
     *  entitled to use - see {@link NetProtocol#sanitizePowerUpId}. Empty/unfilled slots and any id
     *  the host's catalog doesn't recognize are silently dropped rather than trusted. */
    private static Set<String> resolveAllowedPowerUpIds(List<String> loadout) {
        Set<String> allowed = new HashSet<>();
        if (loadout != null) {
            for (String id : loadout) {
                if (id != null && !id.isEmpty() && Catalog.findPowerUp(id).isPresent()) {
                    allowed.add(id);
                }
            }
        }
        return allowed;
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

    /** Whether this host chose to play this match ranked (see {@link #ranked}). */
    public boolean isRanked() {
        return ranked;
    }

    /** The joiner's ranked-ladder player id (see {@code RankClient}), or {@code ""} if they
     *  connected without one (an older client, or a punch packet that isn't a real handshake). */
    public String getJoinerPlayerId() {
        return joinerPlayerId;
    }

    /** The most recently received joiner input, resolved into the same {@link PaddleInput} shape
     *  the AI/local player already feed into {@code MatchSimulation.tick}, or a neutral/no-op
     *  input if nothing has arrived yet. */
    public PaddleInput pollJoinerPaddleInput() {
        NetProtocol.InputMessage msg = latestInput.get();
        // Host-authoritative ownership check: never honor a power-up id the joiner didn't declare
        // (and this host didn't independently confirm exists) in its HELLO - see handleHello /
        // resolveAllowedPowerUpIds. A forged/free-form id from a modified client is silently
        // dropped here (treated as "no activation"), same as any other malformed input.
        String sanitizedPowerUpId = NetProtocol.sanitizePowerUpId(msg.powerUpId(), joinerAllowedPowerUpIds);
        PowerUpDefinition activated = sanitizedPowerUpId.isEmpty()
                ? null
                : Catalog.findPowerUp(sanitizedPowerUpId).orElse(null);
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

    /** True once a joiner has connected and then gone silent for {@link #DISCONNECT_TIMEOUT_MS} -
     *  their process died, or the network dropped. False before any joiner ever connected (that's
     *  just "still waiting", not a disconnect). */
    public boolean isJoinerTimedOut() {
        return joinerAddress != null && lastJoinerPacketAt > 0
                && System.currentTimeMillis() - lastJoinerPacketAt > DISCONNECT_TIMEOUT_MS;
    }

    /** Proposes a rematch to the connected joiner. No-op if there's no joiner (or it already timed
     *  out) - callers should check {@link #hasJoiner()}/{@link #isJoinerTimedOut()} first. */
    public void sendRematchRequest() {
        InetSocketAddress to = joinerAddress;
        if (to != null) {
            sendRaw(to, NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_REQUEST));
        }
    }

    public void sendRematchAccept() {
        InetSocketAddress to = joinerAddress;
        if (to != null) {
            sendRaw(to, NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_ACCEPT));
        }
    }

    public void sendRematchDecline() {
        InetSocketAddress to = joinerAddress;
        if (to != null) {
            sendRaw(to, NetProtocol.encodeHandshake(NetProtocol.TYPE_REMATCH_DECLINE));
        }
    }

    /** See {@link NetProtocol#TYPE_KEEPALIVE} - {@code MatchEndState} calls this periodically so
     *  {@link #isJoinerTimedOut()} doesn't fire just because the match ended and nothing else is
     *  being sent while the result screen is up. */
    public void sendKeepAlive() {
        InetSocketAddress to = joinerAddress;
        if (to != null) {
            sendRaw(to, NetProtocol.encodeHandshake(NetProtocol.TYPE_KEEPALIVE));
        }
    }

    public boolean isRematchRequestedByPeer() {
        return rematchRequestedByJoiner;
    }

    public boolean isRematchAccepted() {
        return rematchAcceptedByJoiner;
    }

    public boolean isRematchDeclined() {
        return rematchDeclinedByJoiner;
    }

    /** Clears all rematch flags - call once a rematch proposal has been resolved (accepted,
     *  declined, timed out, or a fresh match has actually started) so stale state from this round
     *  doesn't leak into the next. */
    public void resetRematchState() {
        rematchRequestedByJoiner = false;
        rematchAcceptedByJoiner = false;
        rematchDeclinedByJoiner = false;
    }

    /** Relays the joiner's own authoritative post-match {@link RankState} (from the Lambda's
     *  {@code reportMatchResult} response) once this host's report call has succeeded, so the
     *  joiner can display the real result instead of guessing via {@code withDeltaFrom}. No-op if
     *  there's no joiner connected any more (e.g. it disconnected right after match-end) - the
     *  joiner falls back to its own guess in that case. */
    public void sendRankResult(RankState joinerRank) {
        InetSocketAddress to = joinerAddress;
        if (to == null || joinerRank == null) {
            return;
        }
        byte[] data = NetProtocol.encodeRankResult(joinerRank.getTier().name(), joinerRank.getDivision(),
                joinerRank.getLp(), joinerRank.getWins(), joinerRank.getLosses(), joinerRank.getLpChange(),
                joinerRank.wasPromoted(), joinerRank.wasDemoted(), joinerRank.getPromoSeriesResult());
        sendRaw(to, data);
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

    /** True if this host's network is best-effort diagnosed as behind a symmetric NAT (see
     *  {@link StunClient#detectsSymmetricNat}) - direct/hole-punched internet play from here is
     *  unlikely to work. Always {@code false} for a LAN-only host (no public address discovered at
     *  all). Callers ({@code MultiplayerState}) should surface a clear warning instead of letting
     *  internet-code registration/punching silently hang. */
    public boolean isSymmetricNatSuspected() {
        return symmetricNatSuspected;
    }

    /** Registers this host with the AWS lobby broker and returns a short code the joiner can
     *  enter instead of typing this machine's raw address. Blocking network call - run off the
     *  render thread. Throws if no public address was discovered (offline, or STUN blocked). The
     *  returned code is also retained (see {@link #getLobbyCode()}) so a ranked match-result
     *  report at the end of the match can reference the actual session that was brokered. */
    public String registerLobby() throws IOException {
        if (publicAddress == null) {
            throw new IOException("no public address available for internet play");
        }
        String code = LobbyClient.create(StunClient.format(publicAddress), localPlayerId);
        lobbyCode = code;
        return code;
    }

    /** The AWS lobby code this host registered under via {@link #registerLobby()}, or
     *  {@code null} for a LAN-direct match (registration never attempted, failed, or hasn't
     *  completed yet). A ranked match report includes this so the backend can verify the report
     *  is backed by a real session instead of trusting an unverified claim - see
     *  {@code aws/README.md} "Trust model". {@code null} here means the eventual report simply
     *  can't be tied to a session (LAN-direct matches never touch the lobby table at all), a
     *  known, accepted tradeoff rather than an oversight. */
    public String getLobbyCode() {
        return lobbyCode;
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
