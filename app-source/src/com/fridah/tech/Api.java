package com.fridah.tech;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Backend API client for fridahyobbytech.onrender.com (config, auth, free net, payments, live SSE). */
public class Api {

    public static final String BASE = "https://fridahyobbytech.onrender.com";

    public interface Cb { void done(boolean ok, JSONObject data, String err); }
    public interface StreamCb { void onEvent(String event, JSONObject data); }

    private static final ExecutorService pool = Executors.newCachedThreadPool();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static void respond(final Cb cb, final boolean ok, final JSONObject data, final String err) {
        main.post(() -> { if (cb != null) cb.done(ok, data, err); });
    }

    private static void call(final String method, final String path, final JSONObject body, final String token, final Cb cb) {
        pool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(BASE + path).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("Accept", "application/json");
                if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(b);
                    os.flush();
                    os.close();
                }
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String txt = readAll(is == null ? conn.getInputStream() : is);
                JSONObject j = new JSONObject(txt.isEmpty() ? "{}" : txt);
                if (code >= 200 && code < 300) respond(cb, true, j, null);
                else respond(cb, false, j, j.optString("error", "HTTP " + code));
            } catch (Exception e) {
                respond(cb, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) { }
            }
        });
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[2048];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        try { r.close(); } catch (Exception ignored) { }
        return sb.toString();
    }

    public static void config(Cb cb) { call("GET", "/api/config", null, null, cb); }

    public static void register(String phone, String pin, String imei, String device, Cb cb) {
        try {
            JSONObject o = new JSONObject().put("phone", phone).put("pin", pin).put("imei", imei).put("device", device);
            call("POST", "/api/auth/register", o, null, cb);
        } catch (Exception e) { respond(cb, false, null, e.getMessage()); }
    }

    public static void login(String phone, String pin, String imei, String device, Cb cb) {
        try {
            JSONObject o = new JSONObject().put("phone", phone).put("pin", pin).put("imei", imei).put("device", device);
            call("POST", "/api/auth/login", o, null, cb);
        } catch (Exception e) { respond(cb, false, null, e.getMessage()); }
    }

    public static void me(String token, Cb cb) { call("GET", "/api/me", null, token, cb); }

    public static void freenetStart(String token, String imei, Cb cb) {
        try {
            call("POST", "/api/freenet/start", new JSONObject().put("imei", imei), token, cb);
        } catch (Exception e) { respond(cb, false, null, e.getMessage()); }
    }

    public static void freenetStop(String token, String imei, Cb cb) {
        try {
            call("POST", "/api/freenet/stop", new JSONObject().put("imei", imei), token, cb);
        } catch (Exception e) { respond(cb, false, null, e.getMessage()); }
    }

    public static void payInitiate(String token, String packageId, String phone, Cb cb) {
        try {
            call("POST", "/api/pay/initiate", new JSONObject().put("packageId", packageId).put("phone", phone), token, cb);
        } catch (Exception e) { respond(cb, false, null, e.getMessage()); }
    }

    public static void payStatus(String token, String paymentId, Cb cb) { call("GET", "/api/pay/status/" + paymentId, null, token, cb); }

    /** LIVE payment status stream (SSE). Returns a handle; call stop() when the dialog closes. */
    public static StreamHandle payStream(String token, String paymentId, final StreamCb cb) {
        final StreamHandle h = new StreamHandle();
        pool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(BASE + "/api/pay/stream/" + paymentId + "?token=" + token).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0); // no timeout: stays open until paid/closed
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("Cache-Control", "no-cache");
                InputStream is = conn.getInputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line, event = "message";
                while (!h.stop.get() && (line = r.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        JSONObject j;
                        try { j = new JSONObject(data); } catch (Exception e) { j = new JSONObject(); }
                        final String ev = event;
                        final JSONObject dj = j;
                        main.post(() -> { if (!h.stop.get() && cb != null) cb.onEvent(ev, dj); });
                        event = "message";
                    }
                }
            } catch (Exception e) {
                if (!h.stop.get()) main.post(() -> { if (cb != null) cb.onEvent("closed", null); });
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Exception ignored) { }
            }
        });
        return h;
    }

    public static class StreamHandle {
        public final AtomicBoolean stop = new AtomicBoolean(false);
        public void stop() { stop.set(true); }
    }
}
