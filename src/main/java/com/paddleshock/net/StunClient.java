package com.paddleshock.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Minimal STUN (RFC 5389) binding-request client used to discover this machine's public
 * ip:port as seen from the internet - the first step of LAN-over-internet multiplayer via UDP
 * hole-punching (see aws/README.md for the overall design).
 *
 * <p>Must run on the SAME {@link DatagramSocket} (and therefore the same local port) that will
 * carry actual game traffic - that's what makes the discovered address useful for punching a
 * hole in this machine's NAT. Callers must do this BEFORE starting any background thread that
 * also reads from the same socket (see {@code NetHost}/{@code NetClient}), since this class does
 * its own blocking {@code socket.receive()} calls; two threads racing to read one socket would
 * non-deterministically steal each other's packets.
 */
public final class StunClient {

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int TYPE_BINDING_REQUEST = 0x0001;
    private static final int TYPE_BINDING_SUCCESS = 0x0101;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final byte FAMILY_IPV4 = 0x01;

    /** Public STUN servers with no auth required, used only for address discovery - they never
     *  see any game traffic. Google's are free, well-known, and highly available. */
    private static final String[] DEFAULT_SERVERS = {
            "stun.l.google.com:19302",
            "stun1.l.google.com:19302",
            "stun2.l.google.com:19302",
    };

    private static final int PER_SERVER_TIMEOUT_MS = 600;

    private StunClient() {
    }

    /** Formats an address the way the rest of the multiplayer code (lobby codes, the JOIN
     *  screen's IP:port field) expects. */
    public static String format(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /** Parses the "ip:port" text {@link #format} produces (also what the lobby broker returns
     *  and what the JOIN screen's text field accepts). */
    public static InetSocketAddress parseAddress(String text) throws IOException {
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new IOException("malformed address: " + text);
        }
        try {
            InetAddress host = InetAddress.getByName(text.substring(0, colon));
            int port = Integer.parseInt(text.substring(colon + 1));
            return new InetSocketAddress(host, port);
        } catch (NumberFormatException e) {
            throw new IOException("malformed address: " + text);
        }
    }

    /** Tries each of {@link #DEFAULT_SERVERS} in turn until one answers, returning this socket's
     *  public ip:port as seen by that server. Returns {@code null} if every server times out
     *  (no internet, or STUN traffic is firewalled) - callers should fall back to LAN-only
     *  direct connect in that case rather than fail outright. */
    public static InetSocketAddress discoverPublicAddress(DatagramSocket socket) {
        for (String server : DEFAULT_SERVERS) {
            int colon = server.lastIndexOf(':');
            String host = server.substring(0, colon);
            int port = Integer.parseInt(server.substring(colon + 1));
            try {
                InetSocketAddress result = query(socket, host, port);
                if (result != null) {
                    return result;
                }
            } catch (IOException e) {
                // this server didn't answer (or DNS failed) - try the next one
            }
        }
        return null;
    }

    private static InetSocketAddress query(DatagramSocket socket, String stunHost, int stunPort) throws IOException {
        byte[] transactionId = new byte[12];
        new SecureRandom().nextBytes(transactionId);
        byte[] request = encodeBindingRequest(transactionId);
        InetSocketAddress stunAddress = new InetSocketAddress(InetAddress.getByName(stunHost), stunPort);

        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(PER_SERVER_TIMEOUT_MS);
            socket.send(new DatagramPacket(request, request.length, stunAddress));

            byte[] buffer = new byte[512];
            long deadline = System.currentTimeMillis() + PER_SERVER_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // throws SocketTimeoutException once the deadline passes
                InetSocketAddress mapped = decodeBindingResponse(
                        Arrays.copyOfRange(packet.getData(), 0, packet.getLength()), transactionId);
                if (mapped != null) {
                    return mapped;
                }
                // stray/unrelated packet (e.g. a late reply from a previous attempt) - keep waiting
            }
            return null;
        } finally {
            socket.setSoTimeout(originalTimeout);
        }
    }

    private static byte[] encodeBindingRequest(byte[] transactionId) {
        byte[] request = new byte[20];
        request[0] = (byte) (TYPE_BINDING_REQUEST >>> 8);
        request[1] = (byte) TYPE_BINDING_REQUEST;
        // bytes 2-3 (attribute length) stay zero - no attributes
        request[4] = (byte) (MAGIC_COOKIE >>> 24);
        request[5] = (byte) (MAGIC_COOKIE >>> 16);
        request[6] = (byte) (MAGIC_COOKIE >>> 8);
        request[7] = (byte) MAGIC_COOKIE;
        System.arraycopy(transactionId, 0, request, 8, 12);
        return request;
    }

    /** Returns the mapped address from a STUN binding success response, or {@code null} if this
     *  packet isn't a valid reply to our request (wrong transaction id, error response, IPv6
     *  address we can't use, truncated attribute, etc.) - all treated as "no answer yet" rather
     *  than a hard failure, since a genuine reply may still be in flight. */
    private static InetSocketAddress decodeBindingResponse(byte[] data, byte[] expectedTransactionId) {
        if (data.length < 20) {
            return null;
        }
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int length = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int cookie = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

        if (cookie != MAGIC_COOKIE || type != TYPE_BINDING_SUCCESS
                || !Arrays.equals(Arrays.copyOfRange(data, 8, 20), expectedTransactionId)) {
            return null;
        }

        int pos = 20;
        int end = Math.min(20 + length, data.length);
        InetSocketAddress fallback = null; // MAPPED-ADDRESS, used only if XOR-MAPPED-ADDRESS is absent

        while (pos + 4 <= end) {
            int attrType = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int attrLen = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            int valueStart = pos + 4;
            int valueEnd = valueStart + attrLen;
            if (valueEnd > data.length) {
                break; // malformed/truncated attribute - stop parsing what we have
            }

            boolean isMapped = attrType == ATTR_MAPPED_ADDRESS;
            boolean isXorMapped = attrType == ATTR_XOR_MAPPED_ADDRESS;
            if ((isMapped || isXorMapped) && attrLen >= 8 && data[valueStart + 1] == FAMILY_IPV4) {
                int rawPort = ((data[valueStart + 2] & 0xFF) << 8) | (data[valueStart + 3] & 0xFF);
                byte[] addrBytes = Arrays.copyOfRange(data, valueStart + 4, valueStart + 8);
                try {
                    if (isXorMapped) {
                        int port = rawPort ^ (MAGIC_COOKIE >>> 16);
                        byte[] cookieBytes = {
                                (byte) (MAGIC_COOKIE >>> 24), (byte) (MAGIC_COOKIE >>> 16),
                                (byte) (MAGIC_COOKIE >>> 8), (byte) MAGIC_COOKIE,
                        };
                        for (int i = 0; i < 4; i++) {
                            addrBytes[i] ^= cookieBytes[i];
                        }
                        return new InetSocketAddress(InetAddress.getByAddress(addrBytes), port);
                    } else if (fallback == null) {
                        fallback = new InetSocketAddress(InetAddress.getByAddress(addrBytes), rawPort);
                    }
                } catch (UnknownHostException e) {
                    // 4 raw bytes always form a valid IPv4 address - unreachable in practice
                }
            }

            int padding = (4 - (attrLen % 4)) % 4;
            pos = valueEnd + padding;
        }
        return fallback;
    }
}
