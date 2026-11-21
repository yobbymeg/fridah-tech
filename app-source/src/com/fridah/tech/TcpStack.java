package com.fridah.tech;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Minimal userspace TCP/IP endpoint for the TUN interface.
 * Terminates TCP connections coming from the device, forwards payloads through
 * SSH channels (BridgeFactory) and answers the device's kernel with well-formed
 * packets (SYN-ACK, ACK, PSH, FIN, RST + retransmissions + window handling).
 *
 * To keep the state machine simple the SYN-ACK carries an MSS option but no
 * window-scale / SACK / timestamps options, so all window values stay literal.
 */
public class TcpStack {

    public interface Sink { void sendPacket(byte[] pkt); }

    public interface Bridge {
        void write(byte[] b, int off, int len);
        void close();
    }

    public interface BridgeFactory {
        /** Async. When the channel is ready call stack.attachBridge(); on failure stack.bridgeFailed(). */
        void open(TcpSession session, String dstHost, int dstPort);
    }

    public static final int FIN = 1, SYN = 2, RST = 4, PSH = 8, ACK = 16;
    private static final int MAX_SESSIONS = 220;

    public static class TcpSession {
        public String key;
        public byte[] src, dst;
        public int sport, dport;
        public int state = 0;          // 1 SYN_RCVD, 2 ESTABLISHED, 3 FIN_RCVD(client), 4 FIN_SENT, 5 dead
        public long theirNext;         // next expected seq from client
        public long myNext;            // next seq we use to send
        public long theirAck;          // first seq NOT acked by client
        public int theirWindow = 1024;
        public int mss = 1360;
        public byte[] buf = new byte[96 * 1024];
        public long bufSeq;            // seq number of buf[head]
        public int head, tail;         // buffered bytes [head, tail)
        public List<byte[]> pendingToBridge = new ArrayList<>();
        public Bridge bridge;
        public boolean bridgeOpening, bridgeFailed, eofFromBridge;
        public long lastSend, lastActivity, openedAt;
        public int rto;
    }

    private final Sink sink;
    private final BridgeFactory factory;
    private final Map<String, TcpSession> sessions = new HashMap<>();
    private final Random rnd = new Random();
    private int ipId = 1;

    public TcpStack(Sink sink, BridgeFactory factory) {
        this.sink = sink;
        this.factory = factory;
    }

    /* ------------------------- sequence helpers ------------------------- */
    private static long u32(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
    private static long add(long a, long b) { return (a + b) & 0xFFFFFFFFL; }
    private static long sub(long a, long b) { return (a - b) & 0xFFFFFFFFL; }
    private static long now() { return System.currentTimeMillis(); }

    private static String keyOf(byte[] a, int pa, byte[] b, int pb) {
        return (a[0] & 0xFF) + "." + (a[1] & 0xFF) + "." + (a[2] & 0xFF) + "." + (a[3] & 0xFF) + ":" + pa
                + "-" + (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF) + ":" + pb;
    }

    /* ------------------------- entry point ------------------------- */
    public synchronized void handle(byte[] pkt, int len) {
        try {
            _handle(pkt, len);
        } catch (Throwable ignored) { }
    }

    private void _handle(byte[] pkt, int len) {
        if (len < 40) return;
        if (((pkt[0] >>> 4) & 0xF) != 4) return;
        int ihl = (pkt[0] & 0xF) * 4;
        int proto = pkt[9] & 0xFF;
        int total = ((pkt[2] & 0xFF) << 8) | (pkt[3] & 0xFF);
        if (total > len) total = len;
        if (proto != 6) return;
        if (ihl < 20 || ihl >= total) return;

        int o = ihl;
        int sport = ((pkt[o] & 0xFF) << 8) | (pkt[o + 1] & 0xFF);
        int dport = ((pkt[o + 2] & 0xFF) << 8) | (pkt[o + 3] & 0xFF);
        long seq = u32(pkt, o + 4);
        long ack = u32(pkt, o + 8);
        int doff = ((pkt[o + 12] >>> 4) & 0xF) * 4;
        if (doff < 20 || o + doff > total) return;
        int flags = pkt[o + 13] & 0xFF;
        int window = ((pkt[o + 14] & 0xFF) << 8) | (pkt[o + 15] & 0xFF);
        int payOff = o + doff;
        int payLen = total - payOff;
        if (payLen < 0) payLen = 0;
        byte[] src = new byte[]{pkt[12], pkt[13], pkt[14], pkt[15]};
        byte[] dst = new byte[]{pkt[16], pkt[17], pkt[18], pkt[19]};
        String key = keyOf(src, sport, dst, dport);
        TcpSession s = sessions.get(key);

        /* --- new connection --- */
        if ((flags & SYN) != 0 && (flags & ACK) == 0) {
            if (s != null) destroy(key);
            if (sessions.size() >= MAX_SESSIONS) {
                TcpSession oldest = null;
                for (TcpSession x : sessions.values()) if (oldest == null || x.lastActivity < oldest.lastActivity) oldest = x;
                if (oldest != null) destroy(oldest.key);
            }
            s = new TcpSession();
            s.key = key; s.src = src; s.dst = dst; s.sport = sport; s.dport = dport;
            s.theirNext = add(seq, 1);
            s.theirWindow = window <= 0 ? 2048 : window;
            int m = parseMss(pkt, o, doff);
            s.mss = m > 0 && m < 1360 ? m : 1360;
            long isn = rnd.nextInt() & 0xFFFFFFFFL;
            s.myNext = add(isn, 1);
            s.theirAck = isn;
            s.bufSeq = s.myNext;
            s.state = 1;
            s.lastActivity = now();
            s.openedAt = now();
            sessions.put(key, s);
            sendTcp(s, isn, add(seq, 1), SYN | ACK, 65535, null, 0, true);
            return;
        }

        if (s == null) {
            if ((flags & RST) == 0) {
                sendTcpRaw(dst, src, dport, sport, seq, 0, RST | ACK, 0, null, 0, false);
            }
            return;
        }

        s.lastActivity = now();

        if ((flags & RST) != 0) { destroy(key); return; }

        if ((flags & ACK) != 0) {
            long d = sub(ack, s.theirAck);
            if (d > 0 && d < 0x80000000L) {
                s.theirAck = ack;
                s.rto = 0;
                dropAcked(s);
            }
            if (s.state == 1) {
                s.state = 2;
                openBridge(s);
            }
            s.theirWindow = window <= 0 ? s.theirWindow : window;
        }

        boolean replied = false;
        if (payLen > 0 && s.state >= 2 && s.state != 4 && s.state != 5) {
            long dseq = sub(seq, s.theirNext);
            if (dseq == 0) {
                byte[] data = new byte[payLen];
                System.arraycopy(pkt, payOff, data, 0, payLen);
                s.theirNext = add(seq, payLen);
                deliver(s, data);
                sendTcp(s, s.myNext, s.theirNext, ACK, 65535, null, 0, false);
                replied = true;
            } else if (dseq > 0 && dseq < 0x80000000L) {
                sendTcp(s, s.myNext, s.theirNext, ACK, 65535, null, 0, false); // dup-ack triggers client retransmit
                replied = true;
            }
        }

        if ((flags & FIN) != 0 && s.state != 4 && s.state != 5) {
            long finSeq = add(seq, payLen);
            long dfin = sub(finSeq, s.theirNext);
            if (dfin == 0) {
                s.theirNext = add(finSeq, 1);
                if (s.state == 2) s.state = 3;
                sendTcp(s, s.myNext, s.theirNext, ACK, 65535, null, 0, false);
                replied = true;
                tryFin(s);
            }
        }

        if (!replied && (flags & ACK) != 0 && s.state != 5) {
            sendTcp(s, s.myNext, s.theirNext, ACK, 65535, null, 0, false);
        }
    }

    /* ------------------------- data path ------------------------- */
    private void deliver(TcpSession s, byte[] data) {
        if (s.bridge != null) {
            try { s.bridge.write(data, 0, data.length); } catch (Throwable t) { bridgeFailed(s); }
        } else if (!s.bridgeFailed) {
            if (s.pendingToBridge.size() < 256) s.pendingToBridge.add(data);
        }
    }

    private void openBridge(TcpSession s) {
        if (s.bridge == null && !s.bridgeOpening && !s.bridgeFailed) {
            s.bridgeOpening = true;
            s.openedAt = now();
            String host = (s.dst[0] & 0xFF) + "." + (s.dst[1] & 0xFF) + "." + (s.dst[2] & 0xFF) + "." + (s.dst[3] & 0xFF);
            factory.open(s, host, s.dport);
        }
    }

    /** Called by the bridge factory when the SSH channel is ready. */
    public synchronized void attachBridge(TcpSession s, Bridge b) {
        if (s == null || s.state >= 4) { try { b.close(); } catch (Throwable ignored) { } return; }
        s.bridge = b;
        s.bridgeOpening = false;
        for (byte[] d : s.pendingToBridge) {
            try { b.write(d, 0, d.length); } catch (Throwable t) { bridgeFailed(s); return; }
        }
        s.pendingToBridge.clear();
    }

    /** Called by the bridge factory when opening failed. */
    public synchronized void bridgeFailed(TcpSession s) {
        if (s == null || s.state >= 4) return;
        s.bridgeOpening = false;
        s.bridgeFailed = true;
        sendTcp(s, 0, 0, RST | ACK, 0, null, 0, false);
        destroy(s.key);
    }

    /** Called by the bridge pump thread when data arrives from the internet. */
    public synchronized void pushToClient(TcpSession s, byte[] data, int len) {
        if (s == null || s.state >= 4 || len <= 0) return;
        try {
            int need = (s.tail - s.head) + len;
            if (need > s.buf.length) {
                // compact
                System.arraycopy(s.buf, s.head, s.buf, 0, s.tail - s.head);
                s.tail -= s.head;
                s.bufSeq = add(s.bufSeq, s.head);
                s.head = 0;
                if (need > s.buf.length) {
                    int cap = Math.max(need + 4096, s.buf.length * 2);
                    s.buf = new byte[cap];
                }
            } else if (s.tail + len > s.buf.length) {
                System.arraycopy(s.buf, s.head, s.buf, 0, s.tail - s.head);
                s.tail -= s.head;
                s.bufSeq = add(s.bufSeq, s.head);
                s.head = 0;
            }
            System.arraycopy(data, 0, s.buf, s.tail, len);
            s.tail += len;
            trySend(s);
        } catch (Throwable ignored) { }
    }

    private void trySend(TcpSession s) {
        while (s.state == 2 || s.state == 3) {
            int win = s.theirWindow;
            if (win <= 0) return;
            long inFlight = sub(s.myNext, s.theirAck);
            long allowed = (long) win - inFlight;
            if (allowed <= 0) return;
            long fromSeq = s.myNext;
            int from = (int) sub(fromSeq, s.bufSeq);
            if (from < s.head || from >= s.tail) return;
            int avail = s.tail - from;
            int n = (int) Math.min(allowed, avail);
            if (n > s.mss) n = s.mss;
            byte[] seg = new byte[n];
            System.arraycopy(s.buf, from, seg, 0, n);
            sendTcp(s, s.myNext, s.theirNext, PSH | ACK, 65535, seg, n, false);
            s.myNext = add(s.myNext, n);
            s.lastSend = now();
        }
    }

    private void dropAcked(TcpSession s) {
        long n = sub(s.theirAck, add(s.bufSeq, s.head));
        if (n > 0 && n <= (s.tail - s.head)) s.head += (int) n;
        else if (n > (s.tail - s.head)) s.head = s.tail;
        if (s.head > 32768 && s.head == s.tail) { s.head = 0; s.tail = 0; s.bufSeq = s.myNext; }
        else if (s.head > 32768) {
            System.arraycopy(s.buf, s.head, s.buf, 0, s.tail - s.head);
            s.tail -= s.head;
            s.bufSeq = add(s.bufSeq, s.head);
            s.head = 0;
        }
    }

    /** Called by the bridge when the internet side closed (EOF). */
    public synchronized void bridgeEof(TcpSession s) {
        if (s == null) return;
        s.eofFromBridge = true;
        if (s.bridge != null) { try { s.bridge.close(); } catch (Throwable ignored) { } s.bridge = null; }
        tryFin(s);
    }

    private void tryFin(TcpSession s) {
        if (s.state != 3) return;
        boolean drained = s.head == s.tail && s.pendingToBridge.isEmpty();
        if ((s.eofFromBridge || s.bridgeFailed || (s.bridge == null && !s.bridgeOpening)) && drained) {
            sendTcp(s, s.myNext, s.theirNext, FIN | ACK, 65535, null, 0, false);
            s.myNext = add(s.myNext, 1);
            s.state = 4;
            s.lastSend = now();
        }
    }

    /* ------------------------- maintenance ------------------------- */
    public synchronized void tick() {
        long t = now();
        List<String> dead = new ArrayList<>();
        for (TcpSession s : sessions.values()) {
            if (s.state == 1 && t - s.openedAt > 20000) { dead.add(s.key); continue; }
            if (s.bridgeOpening && t - s.openedAt > 20000) { bridgeFailed(s); dead.add(s.key); continue; }
            if (s.state == 2 && sub(s.myNext, s.theirAck) > 0 && s.lastSend > 0 && t - s.lastSend > 1300) {
                s.myNext = s.theirAck;   // rewind: retransmit from first unacked byte
                s.rto++;
                if (s.rto > 8) { dead.add(s.key); continue; }
                trySend(s);
            }
            if (s.state == 3 && s.eofFromBridge) tryFin(s);
            if (s.state == 3 && t - s.lastActivity > 8000) tryFin(s);
            if (s.state == 4 && t - s.lastSend > 5000) { dead.add(s.key); continue; }
            if (t - s.lastActivity > 900000) { dead.add(s.key); }
        }
        for (String k : dead) destroy(k);
    }

    public synchronized void destroyAll() {
        for (String k : new ArrayList<>(sessions.keySet())) destroy(k);
        sessions.clear();
    }

    private void destroy(String key) {
        TcpSession s = sessions.remove(key);
        if (s == null) return;
        if (s.bridge != null) { try { s.bridge.close(); } catch (Throwable ignored) { } s.bridge = null; }
    }

    /* ------------------------- packet crafting ------------------------- */
    private void sendTcp(TcpSession s, long seq, long ack, int flags, int window, byte[] payload, int payLen, boolean withMss) {
        sendTcpRaw(s.dst, s.src, s.dport, s.sport, seq, ack, flags, window, payload, payLen, withMss);
    }

    private void sendTcpRaw(byte[] srcIp, byte[] dstIp, int sport, int dport, long seq, long ack,
                            int flags, int window, byte[] payload, int payLen, boolean withMss) {
        try {
            int tcpHdr = 20 + (withMss ? 4 : 0);
            int total = 20 + tcpHdr + payLen;
            byte[] out = new byte[total];
            out[0] = 0x45;
            out[2] = (byte) (total >> 8);
            out[3] = (byte) (total & 0xFF);
            int id = (ipId++) & 0xFFFF;
            out[4] = (byte) (id >> 8);
            out[5] = (byte) (id & 0xFF);
            out[6] = (byte) 0x40;
            out[7] = 0;
            out[8] = 64;
            out[9] = 6;
            System.arraycopy(srcIp, 0, out, 12, 4);
            System.arraycopy(dstIp, 0, out, 16, 4);
            int o = 20;
            out[o] = (byte) (sport >> 8); out[o + 1] = (byte) sport;
            out[o + 2] = (byte) (dport >> 8); out[o + 3] = (byte) dport;
            w32(out, o + 4, seq);
            w32(out, o + 8, ack);
            out[o + 12] = (byte) ((tcpHdr / 4) << 4);
            out[o + 13] = (byte) flags;
            out[o + 14] = (byte) (window >> 8);
            out[o + 15] = (byte) window;
            if (withMss) {
                out[o + 20] = 2;
                out[o + 21] = 4;
                out[o + 22] = (byte) (1360 >> 8);
                out[o + 23] = (byte) (1360 & 0xFF);
            }
            if (payLen > 0 && payload != null) System.arraycopy(payload, 0, out, o + tcpHdr, payLen);
            int tcpLen = tcpHdr + payLen;
            int cs = checksum(out, o, tcpLen, srcIp, dstIp, 6);
            out[o + 16] = (byte) (cs >> 8);
            out[o + 17] = (byte) (cs & 0xFF);
            int ics = checksum(out, 0, 20, null, null, 0);
            out[10] = (byte) (ics >> 8);
            out[11] = (byte) (ics & 0xFF);
            sink.sendPacket(out);
        } catch (Throwable ignored) { }
    }

    private static void w32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >>> 24) & 0xFF);
        b[off + 1] = (byte) ((v >>> 16) & 0xFF);
        b[off + 2] = (byte) ((v >>> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    /** Internet checksum. For TCP pass srcIp/dstIp + proto; for IP header pass nulls and proto 0. */
    static int checksum(byte[] b, int off, int len, byte[] srcIp, byte[] dstIp, int proto) {
        long sum = 0;
        if (srcIp != null) {
            sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
            sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
            sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
            sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
            sum += proto & 0xFF;
            sum += len & 0xFFFF;
        }
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < end) sum += (b[i] & 0xFF) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return (int) ((~sum) & 0xFFFF);
    }

    private static int parseMss(byte[] pkt, int tcpOff, int doff) {
        int opt = tcpOff + 20;
        int end = tcpOff + doff;
        while (opt + 1 < end) {
            int kind = pkt[opt] & 0xFF;
            if (kind == 0) break;
            if (kind == 1) { opt++; continue; }
            int l = pkt[opt + 1] & 0xFF;
            if (l < 2 || opt + l > end) break;
            if (kind == 2 && l == 4) return ((pkt[opt + 2] & 0xFF) << 8) | (pkt[opt + 3] & 0xFF);
            opt += l;
        }
        return -1;
    }
}
