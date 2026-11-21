package com.fridah.tech;

import android.net.VpnService;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Connects through the carrier proxy, injects the payload, watches the HTTP
 * responses for 302 and, if the door is open, runs the SSH handshake over the
 * very same socket with JSch.
 */
public class TunnelConnector {

    public interface Listener {
        void onPing(int ms);
        /** called when a proxy answered 302 and we switch to the next one (idx is the next index) */
        void onProxySwitch(int nextIdx, int total);
        void onSshPhase();
        void onConnected(Session ssh);
        void onFail(String message, int code); // code 302 = all proxies returned 302
    }

    private final VpnService vpn;
    private final FridahConfig cfg;
    private final Listener listener;
    private volatile boolean cancelled = false;

    public TunnelConnector(VpnService vpn, FridahConfig cfg, Listener listener) {
        this.vpn = vpn;
        this.cfg = cfg;
        this.listener = listener;
    }

    public void cancel() { cancelled = true; }

    public void run() {
        String lastErr = null;
        boolean saw302 = false;
        for (int pi = 0; pi < cfg.proxies.length && !cancelled; pi++) {
            String proxy = cfg.proxies[pi];
            String[] hp = hostPort(proxy);
            Socket s = null;
            try {
                long t0 = System.currentTimeMillis();
                s = new Socket();
                try { vpn.protect(s); } catch (Throwable ignored) { }
                int proxyPort = 80;
                try { proxyPort = Integer.parseInt(hp[1]); } catch (Exception ignored) { }
                s.connect(new InetSocketAddress(hp[0], proxyPort), 12000);
                s.setTcpNoDelay(true);
                int ping = (int) (System.currentTimeMillis() - t0);
                listener.onPing(ping);
                if (cancelled) return;

                InputStream is = s.getInputStream();
                OutputStream os = s.getOutputStream();
                int lastCode = -1;
                boolean switched = false;

                for (int pi2 = 0; pi2 < cfg.parts.length; pi2++) {
                    FridahConfig.Part part = cfg.parts[pi2];
                    byte[] data = cfg.render(part.data, proxy).getBytes("ISO-8859-1");
                    os.write(data);
                    os.flush();
                    if (part.waitResponse) {
                        int code = readStatus(is, s, 30000);
                        lastCode = code;
                        if (code == 302) {
                            saw302 = true;
                            if (pi < cfg.proxies.length - 1) {
                                listener.onProxySwitch(pi + 1, cfg.proxies.length);
                                switched = true;
                                break;
                            } else {
                                try { s.close(); } catch (Exception ignored) { }
                                String msg = "airtel".equals(cfg.carrier)
                                        ? FridahConfigForUi.msgUseAnotherSim()
                                        : FridahConfigForUi.msgFreeOver();
                                listener.onFail(msg, 302);
                                return;
                            }
                        }
                        if (code != 101 && code != 200 && code != 220) {
                            // unexpected answer from this proxy — try the next one
                            break;
                        }
                    }
                }
                if (switched) { try { s.close(); } catch (Exception ignored) { } continue; }
                if (cancelled) return;

                if (lastCode != 101 && lastCode != 200 && lastCode != 220) {
                    lastErr = lastCode > 0 ? ("Proxy answered HTTP " + lastCode) : "No answer from proxy";
                    try { s.close(); } catch (Exception ignored) { }
                    continue;
                }

                listener.onSshPhase();
                if (cancelled) return;
                Session session = sshConnect(s);
                listener.onConnected(session);
                return;
            } catch (Exception e) {
                lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                try { if (s != null) s.close(); } catch (Exception ignored) { }
            }
        }
        if (!cancelled) {
            listener.onFail(lastErr == null ? "Could not connect" : lastErr, saw302 ? 302 : 0);
        }
    }

    private Session sshConnect(Socket s) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(cfg.user, cfg.sshHost, cfg.sshPort);
        session.setPassword(cfg.pass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setConfig("server_host_key", "ssh-rsa,ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-dss");
        session.setSocketFactory(new SocketFactory() {
            @Override
            public Socket createSocket(String host, int port) { return s; }

            @Override
            public InputStream getInputStream(Socket socket) throws java.io.IOException { return socket.getInputStream(); }

            @Override
            public OutputStream getOutputStream(Socket socket) throws java.io.IOException { return socket.getOutputStream(); }
        });
        session.setTimeout(0);
        session.connect(30000);
        return session;
    }

    /** Reads the HTTP status line of the next response (byte-by-byte so no SSH bytes are consumed). */
    private static int readStatus(InputStream is, Socket s, int timeoutMs) throws Exception {
        s.setSoTimeout(timeoutMs);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        int guard = 0;
        while (guard++ < 16384) {
            b = is.read();
            if (b < 0) throw new Exception("Connection closed while reading response");
            head.write(b);
            int size = head.size();
            if (size >= 4) {
                byte[] h = head.toByteArray();
                if (h[size - 4] == '\r' && h[size - 3] == '\n' && h[size - 2] == '\r' && h[size - 1] == '\n') break;
            }
        }
        try { s.setSoTimeout(0); } catch (Exception ignored) { }
        String first = head.toString("ISO-8859-1");
        int eol = first.indexOf('\n');
        String line = eol > 0 ? first.substring(0, eol).trim() : first.trim();
        // HTTP/1.1 302 Found
        String[] tok = line.split(" ");
        for (int i = 1; i < tok.length; i++) {
            try { return Integer.parseInt(tok[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    private static String[] hostPort(String hp) {
        int i = hp.lastIndexOf(':');
        if (i < 0) return new String[]{hp, "80"};
        return new String[]{hp.substring(0, i), hp.substring(i + 1)};
    }

    /** UI-facing helper strings (kept here so both service and activity use the same words). */
    public static class FridahConfigForUi {
        public static String msgFreeOver() { return "Free net is over. Buy net!"; }
        public static String msgUseAnotherSim() { return "Please use another sim card"; }
    }
}
