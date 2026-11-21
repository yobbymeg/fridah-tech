package com.fridah.tech;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.Session;

import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The VPN tunnel: TUN device -> userspace TCP stack -> SSH channels (JSch).
 * Broadcasts live state + speed to the UI; NO connection logs are shown anywhere.
 */
public class TunnelService extends VpnService implements TcpStack.Sink, TcpStack.BridgeFactory {

    public static final String ACTION_CONNECT = "com.fridah.tech.CONNECT";
    public static final String ACTION_DISCONNECT = "com.fridah.tech.DISCONNECT";
    public static final String ACTION_SET_LIMIT = "com.fridah.tech.SET_LIMIT";
    public static final String ACTION_STATE = "com.fridah.tech.STATE";
    public static final String ACTION_STATS = "com.fridah.tech.STATS";
    public static final String ACTION_PING = "com.fridah.tech.PING";

    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_CODE = "code";       // 0 normal, 302 sim/free-over, 2 free-time-over, 4 package-over
    public static final String EXTRA_DOWN = "down";
    public static final String EXTRA_UP = "up";
    public static final String EXTRA_TOTAL_DOWN = "td";
    public static final String EXTRA_TOTAL_UP = "tu";
    public static final String EXTRA_PING = "ms";

    public static final String ST_DISCONNECTED = "disconnected";
    public static final String ST_CONNECTING = "connecting";
    public static final String ST_CONNECTED = "connected";
    public static final String ST_ERROR = "error";

    public static volatile String currentState = ST_DISCONNECTED;
    public static volatile TunnelService instance = null;
    public static volatile String activeCarrier = null;

    private static final String CHANNEL = "fridah_tech";
    private static final int NOTIF_ID = 47;

    private ParcelFileDescriptor tun;
    private FileInputStream tunIn;
    private FileOutputStream tunOut;
    private Thread tunThread;
    private volatile boolean running = false;
    private Session ssh;
    private TcpStack stack;
    private TunnelConnector connector;
    private final ExecutorService bridgePool = Executors.newCachedThreadPool();
    private final ExecutorService dnsPool = Executors.newFixedThreadPool(3);
    private final Semaphore dnsSlots = new Semaphore(10);
    private final AtomicLong upBytes = new AtomicLong();
    private final AtomicLong downBytes = new AtomicLong();
    private long lastUp, lastDown;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Runnable statsTask, freeOverTask;
    private int freeMinutes = 0, freeMb = 0;
    private long premiumExpiresAt = 0;
    private NotificationManager nm;

    /* ------------------------- lifecycle ------------------------- */
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Fridah Tech VPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("VPN connection status");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_DISCONNECT : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            shutdown(null, 0);
            return START_NOT_STICKY;
        }
        if (ACTION_SET_LIMIT.equals(action)) {
            freeMinutes = intent.getIntExtra("freeMinutes", 0);
            freeMb = intent.getIntExtra("freeMb", 0);
            startFreeGuards();
            return START_STICKY;
        }
        // CONNECT
        String carrier = intent.getStringExtra("carrier");
        freeMinutes = intent.getIntExtra("freeMinutes", 0);
        freeMb = intent.getIntExtra("freeMb", 0);
        premiumExpiresAt = intent.getLongExtra("premiumExpiresAt", 0);
        if (running) return START_STICKY;

        startForeground(NOTIF_ID, buildNotification("Fridah Tech — connecting…"));
        broadcastState(ST_CONNECTING, null, 0);
        activeCarrier = carrier;

        FridahConfig cfg = FridahConfig.forCarrier(carrier);
        if (cfg == null) {
            shutdown("No tunnel configuration for this SIM card", 0);
            return START_NOT_STICKY;
        }
        stack = new TcpStack(this, this);
        connector = new TunnelConnector(this, cfg, new TunnelConnector.Listener() {
            @Override
            public void onPing(int ms) {
                Intent i = new Intent(ACTION_PING).setPackage(getPackageName());
                i.putExtra(EXTRA_PING, ms);
                sendBroadcast(i);
            }

            @Override
            public void onProxySwitch(int nextIdx, int total) {
                // silent switch to the next remote proxy (302 on the previous one)
            }

            @Override
            public void onSshPhase() { }

            @Override
            public void onConnected(Session session) {
                ssh = session;
                if (!establishTun()) {
                    shutdown("Could not create the VPN tunnel", 0);
                    return;
                }
                running = true;
                currentState = ST_CONNECTED;
                startStats();
                startFreeGuards();
                broadcastState(ST_CONNECTED, null, 0);
                updateNotification("Fridah Tech — connected");
            }

            @Override
            public void onFail(String message, int code) {
                shutdown(message, code);
            }
        });
        tunThread = new Thread(() -> connector.run(), "fridah-connect");
        tunThread.start();
        return START_STICKY;
    }

    private boolean establishTun() {
        try {
            VpnService.Builder b = new VpnService.Builder();
            b.setSession("Fridah Tech");
            b.setMtu(1400);
            b.addAddress("10.111.222.3", 32);
            b.addDnsServer("10.111.222.1");
            b.addRoute("0.0.0.0", 0);
            tun = b.establish();
            if (tun == null) return false;
            tunIn = new FileInputStream(tun.getFileDescriptor());
            tunOut = new FileOutputStream(tun.getFileDescriptor());
            tunThread = new Thread(this::readLoop, "fridah-tun");
            tunThread.start();
            return true;
        } catch (Throwable t) {
            Log.e("FridahTech", "establish", t);
            return false;
        }
    }

    /* ------------------------- tun read loop ------------------------- */
    private void readLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            try {
                int n = tunIn.read(buf);
                if (n <= 0) continue;
                upBytes.addAndGet(n);
                if (n < 20) continue;
                int proto = buf[9] & 0xFF;
                if (proto == 6) {
                    stack.handle(buf, n);
                } else if (proto == 17) {
                    handleUdp(buf, n);
                }
            } catch (Throwable t) {
                if (running) { /* transient read error; keep going */ }
            }
        }
    }

    @Override
    public void sendPacket(byte[] pkt) {
        try {
            tunOut.write(pkt);
            tunOut.flush();
            downBytes.addAndGet(pkt.length);
        } catch (Throwable ignored) { }
    }

    /* ------------------------- bridges (SSH channels) ------------------------- */
    @Override
    public void open(final TcpStack.TcpSession session, final String dstHost, final int dstPort) {
        bridgePool.execute(() -> {
            ChannelDirectTCPIP ch = null;
            try {
                ch = (ChannelDirectTCPIP) ssh.openChannel("direct-tcpip");
                ch.setHost(dstHost);
                ch.setPort(dstPort);
                ch.connect(15000);
                InputStream in = ch.getInputStream();
                OutputStream out = ch.getOutputStream();
                final ChannelDirectTCPIP chan = ch;
                TcpStack.Bridge bridge = new TcpStack.Bridge() {
                    @Override
                    public void write(byte[] b, int off, int len) {
                        try { out.write(b, off, len); out.flush(); } catch (Throwable t) {
                            stack.bridgeFailed(session);
                        }
                    }

                    @Override
                    public void close() {
                        try { chan.disconnect(); } catch (Throwable ignored) { }
                    }
                };
                stack.attachBridge(session, bridge);
                byte[] buf = new byte[16384];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        stack.pushToClient(session, chunk, n);
                    }
                    stack.bridgeEof(session);
                } catch (Throwable t) {
                    stack.bridgeEof(session);
                }
            } catch (Throwable t) {
                stack.bridgeFailed(session);
            }
        });
    }

    /* ------------------------- DNS over SSH (UDP 53 -> TCP 53) ------------------------- */
    private void handleUdp(byte[] pkt, int len) {
        try {
            int ihl = (pkt[0] & 0xF) * 4;
            if (ihl + 8 > len) return;
            int dport = ((pkt[ihl + 2] & 0xFF) << 8) | (pkt[ihl + 3] & 0xFF);
            int sport = ((pkt[ihl] & 0xFF) << 8) | (pkt[ihl + 1] & 0xFF);
            int uLen = ((pkt[ihl + 4] & 0xFF) << 8) | (pkt[ihl + 5] & 0xFF);
            int payOff = ihl + 8;
            int payLen = Math.min(uLen - 8, len - payOff);
            if (payLen <= 0) return;
            if (dport != 53) return; // block other UDP (forces QUIC -> TCP fallback)
            final byte[] srcIp = new byte[]{pkt[12], pkt[13], pkt[14], pkt[15]};
            final byte[] dns = new byte[payLen];
            System.arraycopy(pkt, payOff, dns, 0, payLen);
            final int cPort = sport;
            if (!dnsSlots.tryAcquire()) return;
            dnsPool.execute(() -> {
                try {
                    byte[] resp = dnsOverTcp(dns);
                    if (resp == null) {
                        resp = servfail(dns);
                    }
                    sendUdpReply(new byte[]{10, (byte) 111, (byte) 222, 1}, srcIp, 53, cPort, resp);
                } catch (Throwable ignored) {
                } finally {
                    dnsSlots.release();
                }
            });
        } catch (Throwable ignored) { }
    }

    private byte[] dnsOverTcp(byte[] dns) {
        ChannelDirectTCPIP ch = null;
        try {
            ch = (ChannelDirectTCPIP) ssh.openChannel("direct-tcpip");
            ch.setHost("8.8.8.8");
            ch.setPort(53);
            ch.connect(12000);
            OutputStream out = ch.getOutputStream();
            InputStream in = ch.getInputStream();
            byte[] framed = new byte[dns.length + 2];
            framed[0] = (byte) ((dns.length >> 8) & 0xFF);
            framed[1] = (byte) (dns.length & 0xFF);
            System.arraycopy(dns, 0, framed, 2, dns.length);
            out.write(framed);
            out.flush();
            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) return null;
            int rlen = ((hi & 0xFF) << 8) | (lo & 0xFF);
            if (rlen <= 0 || rlen > 65535) return null;
            byte[] resp = new byte[rlen];
            int got = 0;
            while (got < rlen) {
                int n = in.read(resp, got, rlen - got);
                if (n < 0) return null;
                got += n;
            }
            if (resp.length > 1380) {
                byte[] tc = servfail(resp.length > 12 ? resp : dns); // truncated fallback
                if (tc != null && tc.length > 2) { tc[2] |= 0x02; }
                return tc;
            }
            return resp;
        } catch (Throwable t) {
            return null;
        } finally {
            if (ch != null) try { ch.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** Turn a query into a valid SERVFAIL response so resolvers fail fast. */
    private static byte[] servfail(byte[] query) {
        if (query == null || query.length < 12) return null;
        byte[] r = new byte[Math.min(query.length, 512)];
        System.arraycopy(query, 0, r, 0, r.length);
        r[2] |= (byte) 0x80;              // QR = response
        r[3] = (byte) ((r[3] & 0xF0) | 0x02); // RCODE = SERVFAIL
        r[6] = 0; r[7] = 0;               // ANCOUNT = 0
        r[8] = 0; r[9] = 0;               // NSCOUNT = 0
        r[10] = 0; r[11] = 0;             // ARCOUNT = 0
        return r;
    }

    private void sendUdpReply(byte[] srcIp, byte[] dstIp, int sport, int dport, byte[] payload) {
        try {
            int total = 20 + 8 + payload.length;
            byte[] out = new byte[total];
            out[0] = 0x45;
            out[2] = (byte) (total >> 8);
            out[3] = (byte) (total & 0xFF);
            out[6] = 0x40;
            out[8] = 64;
            out[9] = 17;
            System.arraycopy(srcIp, 0, out, 12, 4);
            System.arraycopy(dstIp, 0, out, 16, 4);
            int o = 20;
            out[o] = (byte) (sport >> 8); out[o + 1] = (byte) sport;
            out[o + 2] = (byte) (dport >> 8); out[o + 3] = (byte) dport;
            int uLen = 8 + payload.length;
            out[o + 4] = (byte) (uLen >> 8); out[o + 5] = (byte) uLen;
            System.arraycopy(payload, 0, out, o + 8, payload.length);
            int cs = TcpStack.checksum(out, o, uLen, srcIp, dstIp, 17);
            if (cs == 0) cs = 0xFFFF;
            out[o + 6] = (byte) (cs >> 8);
            out[o + 7] = (byte) (cs & 0xFF);
            int ics = TcpStack.checksum(out, 0, 20, null, null, 0);
            out[10] = (byte) (ics >> 8);
            out[11] = (byte) (ics & 0xFF);
            sendPacket(out);
        } catch (Throwable ignored) { }
    }

    /* ------------------------- stats + guards ------------------------- */
    private void startStats() {
        lastUp = upBytes.get();
        lastDown = downBytes.get();
        statsTask = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                long up = upBytes.get(), down = downBytes.get();
                Intent i = new Intent(ACTION_STATS).setPackage(getPackageName());
                i.putExtra(EXTRA_DOWN, down - lastDown);
                i.putExtra(EXTRA_UP, up - lastUp);
                i.putExtra(EXTRA_TOTAL_DOWN, down);
                i.putExtra(EXTRA_TOTAL_UP, up);
                sendBroadcast(i);
                long total = up + down;
                if (freeMb > 0 && total > (long) freeMb * 1048576L) {
                    shutdown("Free net is over. Buy net!", 2);
                    return;
                }
                if (premiumExpiresAt > 0 && System.currentTimeMillis() > premiumExpiresAt) {
                    shutdown("Your package has ended. Buy net!", 4);
                    return;
                }
                updateNotification("Fridah Tech — \u2193 " + fmt(down - lastDown) + "/s  \u2191 " + fmt(up - lastUp) + "/s");
                lastUp = up;
                lastDown = down;
                h.postDelayed(this, 1000);
            }
        };
        h.postDelayed(statsTask, 1000);
    }

    private void startFreeGuards() {
        if (freeMinutes > 0) {
            freeOverTask = () -> shutdown("Free net is over. Buy net!", 2);
            h.postDelayed(freeOverTask, freeMinutes * 60000L);
        }
    }

    static String fmt(long bps) {
        if (bps >= 1048576) return String.format(java.util.Locale.US, "%.1f MB", bps / 1048576.0);
        if (bps >= 1024) return String.format(java.util.Locale.US, "%.0f KB", bps / 1024.0);
        return bps + " B";
    }

    /* ------------------------- shutdown ------------------------- */
    private void shutdown(String msg, int code) {
        running = false;
        currentState = ST_DISCONNECTED;
        try { if (connector != null) connector.cancel(); } catch (Throwable ignored) { }
        try { if (statsTask != null) h.removeCallbacks(statsTask); } catch (Throwable ignored) { }
        try { if (freeOverTask != null) h.removeCallbacks(freeOverTask); } catch (Throwable ignored) { }
        try { if (stack != null) stack.destroyAll(); } catch (Throwable ignored) { }
        try { if (ssh != null) ssh.disconnect(); } catch (Throwable ignored) { }
        ssh = null;
        try { if (tunIn != null) tunIn.close(); } catch (Throwable ignored) { }
        try { if (tunOut != null) tunOut.close(); } catch (Throwable ignored) { }
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        tunIn = null; tunOut = null; tun = null;
        activeCarrier = null;
        // NOTE: bridge/dns pools stay alive so the user can reconnect immediately
        broadcastState(msg == null ? ST_DISCONNECTED : ST_ERROR, msg, code);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onRevoke() {
        // VPN permission revoked or another VPN started — clean up quietly
        shutdown(null, 0);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        running = false;
        currentState = ST_DISCONNECTED;
        instance = null;
        super.onDestroy();
    }

    /* ------------------------- helpers ------------------------- */
    private void broadcastState(String state, String msg, int code) {
        currentState = state;
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_MSG, msg == null ? "" : msg);
        i.putExtra(EXTRA_CODE, code);
        sendBroadcast(i);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle("Fridah Tech")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        Intent d = new Intent(this, TunnelService.class).setAction(ACTION_DISCONNECT);
        b.addAction(new Notification.Action.Builder(null, "DISCONNECT",
                PendingIntent.getService(this, 1, d, PendingIntent.FLAG_IMMUTABLE)).build());
        return b.build();
    }

    private void updateNotification(String text) {
        try { nm.notify(NOTIF_ID, buildNotification(text)); } catch (Throwable ignored) { }
    }
}
