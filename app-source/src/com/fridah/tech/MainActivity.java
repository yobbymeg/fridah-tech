package com.fridah.tech;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fridah Tech — premium UI shell.
 * Screens: splash -> welcome -> auth -> main -> packages / reels / support.
 * Auto-connects the private VPN for the detected SIM (Airtel / Safaricom),
 * shows live payment confirmations and NO tunnel logs (only Connected / Disconnected).
 */
public class MainActivity extends Activity {

    public static final int APP_VERSION_CODE = 2;
    public static final String APP_VERSION_NAME = "2.0";
    private static final long SPLASH_MS = 3500;
    private static final int REQ_VPN = 41;
    private static final int REQ_PERMS = 42;
    private static final int REQ_INSTALL = 43;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Store store;
    private FrameLayout screens;
    private LiveBgView bg;
    private final java.util.HashMap<String, View> views = new java.util.HashMap<>();

    private JSONObject config = new JSONObject();
    private String carrier;
    private int ping = 0;
    private boolean freeMode = false;
    private long freeEndAt = 0;
    private String pendingBuyPkg = null;
    private Runnable chipTicker;

    // main screen refs
    private PowerButtonView power;
    private TextView statusTxt, speedTxt, sessionTxt, pingTxt, statusDot;
    // auth refs
    private EditText phoneEt, pinEt;
    private TextView authTitle, authSubmit, authSwitch;
    private boolean registerMode = true;
    private TextView authNote;
    private WebView reelsWv;
    private LinearLayout pkgList;

    private AlertDialog payDialog;
    private TextView payStatus;
    private ProgressBar paySpinner;
    private TextView payRetry;
    private String payId;
    private Api.StreamHandle payStream;
    private boolean payPolling = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent i) {
            String a = i.getAction();
            if (TunnelService.ACTION_STATE.equals(a)) {
                String st = i.getStringExtra(TunnelService.EXTRA_STATE);
                String msg = i.getStringExtra(TunnelService.EXTRA_MSG);
                int code = i.getIntExtra(TunnelService.EXTRA_CODE, 0);
                if (TunnelService.ST_CONNECTED.equals(st)) onTunnelConnected();
                else if (TunnelService.ST_CONNECTING.equals(st)) setPowerState(TunnelService.ST_CONNECTING);
                else if (TunnelService.ST_ERROR.equals(st)) onTunnelError(msg, code);
                else setPowerState(TunnelService.ST_DISCONNECTED);
            } else if (TunnelService.ACTION_STATS.equals(a)) {
                long d = i.getLongExtra(TunnelService.EXTRA_DOWN, 0);
                long u = i.getLongExtra(TunnelService.EXTRA_UP, 0);
                long td = i.getLongExtra(TunnelService.EXTRA_TOTAL_DOWN, 0);
                long tu = i.getLongExtra(TunnelService.EXTRA_TOTAL_UP, 0);
                if (speedTxt != null) {
                    speedTxt.setText("\u2193 " + TunnelService.fmt(d) + "/s    \u2191 " + TunnelService.fmt(u)
                            + "     total " + TunnelService.fmt(td + tu));
                }
            } else if (TunnelService.ACTION_PING.equals(a)) {
                ping = i.getIntExtra(TunnelService.EXTRA_PING, 0);
                store.setLastPing(ping);
                updatePing();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        getWindow().setStatusBarColor(0xFF060A14);
        getWindow().setNavigationBarColor(0xFF060A14);

        FrameLayout root = new FrameLayout(this);
        bg = new LiveBgView(this);
        screens = new FrameLayout(this);
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(screens, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        ping = store.lastPing();
        carrier = SimInfo.carrier(this);

        IntentFilter f = new IntentFilter();
        f.addAction(TunnelService.ACTION_STATE);
        f.addAction(TunnelService.ACTION_STATS);
        f.addAction(TunnelService.ACTION_PING);
        registerReceiver(receiver, f);

        buildSplash();
        show("splash");
        requestPerms();

        h.postDelayed(this::finishSplash, SPLASH_MS);

        Api.config((ok, data, err) -> {
            if (ok && data != null) {
                config = data;
                if (data.optJSONObject("maintenance") != null && data.optJSONObject("maintenance").optBoolean("on")) {
                    buildMaintenance(data.optJSONObject("maintenance").optString("message", "Under maintenance"));
                    show("maint");
                    return;
                }
                checkForUpdate();
            }
        });
    }

    /* ==================== SPLASH ==================== */
    private void buildSplash() {
        LinearLayout v = UiKit.column(this);
        v.setGravity(Gravity.CENTER);
        TextView logo = UiKit.label(this, "Fridah Tech", UiKit.TXT, 40, true);
        TextView sub = UiKit.label(this, "Fridah Online Net", UiKit.ACCENT, 15, false);
        ProgressBar pb = new ProgressBar(this);
        LinearLayout pbWrap = new LinearLayout(this);
        pbWrap.setGravity(Gravity.CENTER);
        pbWrap.addView(pb);
        TextView loading = UiKit.label(this, "Detecting your SIM card…", UiKit.MUT, 12, false);
        v.addView(logo);
        v.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 46);
        v.addView(pbWrap, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = UiKit.dp(this, 14);
        v.addView(loading, lp2);
        views.put("splash", v);
    }

    private void finishSplash() {
        // SIM was detected during the splash; now continue the flow
        if (config != null && config.optJSONObject("maintenance") != null
                && config.optJSONObject("maintenance").optBoolean("on")) {
            if (views.get("maint") != null) { show("maint"); return; }
        }
        if (!store.welcomeShown()) {
            buildWelcome();
            show("welcome");
            return;
        }
        if (store.token() == null) {
            buildAuth();
            show("auth");
        } else {
            buildMain();
            show("main");
            autoConnect();
        }
    }

    private void afterWelcome() {
        store.setWelcomeShown();
        if (store.token() == null) {
            buildAuth();
            show("auth");
        } else {
            buildMain();
            show("main");
            autoConnect();
        }
    }

    /* ==================== WELCOME ==================== */
    private void buildWelcome() {
        FrameLayout v = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.panel(this, 26));
        card.setPadding(UiKit.dp(this, 26), UiKit.dp(this, 30), UiKit.dp(this, 26), UiKit.dp(this, 26));
        JSONObject w = config.optJSONObject("welcome");
        String title = w != null ? w.optString("title", "Welcome to the home of tech") : "Welcome to the home of tech";
        String body = w != null && w.optString("body").length() > 0 ? w.optString("body") :
                "Fast, private and unlimited internet for everyone. Your SIM card is detected automatically and the tunnel opens by itself.";
        String phone = w != null ? w.optString("phone", "0795314221") : "0795314221";

        TextView t = UiKit.label(this, title, UiKit.TXT, 23, true);
        TextView b = UiKit.label(this, body, UiKit.MUT, 14, false);
        b.setLineSpacing(UiKit.dp(this, 3), 1f);
        TextView c = UiKit.label(this, "Contact Admin:  " + phone, UiKit.ACCENT, 15, true);
        TextView next = UiKit.button(this, "NEXT", UiKit.ACCENT, UiKit.ACCENT2);
        next.setOnClickListener(x -> afterWelcome());

        card.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 14);
        card.addView(b, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = UiKit.dp(this, 18);
        card.addView(c, lp2);
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp3.topMargin = UiKit.dp(this, 26);
        card.addView(next, lp3);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(UiKit.dp(this, 330), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        v.addView(card, cl);
        views.put("welcome", v);
    }

    /* ==================== AUTH ==================== */
    private void buildAuth() {
        LinearLayout v = UiKit.column(this);
        v.setGravity(Gravity.CENTER);
        int side = UiKit.dp(this, 24);
        v.setPadding(side, side, side, side);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.panel(this, 26));
        card.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 26), UiKit.dp(this, 24), UiKit.dp(this, 24));

        authTitle = UiKit.label(this, "Create your account", UiKit.TXT, 21, true);
        TextView hint = UiKit.label(this, "Phone number + 4 digit PIN", UiKit.MUT, 12, false);

        phoneEt = UiKit.phoneField(this, "07XX XXX XXX");
        pinEt = UiKit.pinField(this, "4-digit PIN");
        pinEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinEt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});

        authSubmit = UiKit.button(this, "REGISTER", UiKit.ACCENT, UiKit.ACCENT2);
        authSubmit.setOnClickListener(x -> submitAuth());
        authSwitch = UiKit.label(this, "Already have an account?  LOGIN", UiKit.ACCENT, 13, true);
        authSwitch.setOnClickListener(x -> {
            registerMode = !registerMode;
            applyAuthMode();
        });
        authNote = UiKit.label(this, "", UiKit.RED, 12, false);

        TextView skip = UiKit.label(this, "Skip for now", UiKit.MUT, 12, false);
        skip.setGravity(Gravity.CENTER);
        skip.setOnClickListener(x -> { buildMain(); show("main"); autoConnect(); });

        card.addView(authTitle);
        card.addView(hint);
        LinearLayout.LayoutParams f1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f1.topMargin = UiKit.dp(this, 18);
        card.addView(phoneEt, f1);
        LinearLayout.LayoutParams f2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        f2.topMargin = UiKit.dp(this, 12);
        card.addView(pinEt, f2);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1.topMargin = UiKit.dp(this, 20);
        card.addView(authSubmit, b1);
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s1.topMargin = UiKit.dp(this, 14);
        card.addView(authSwitch, s1);
        LinearLayout.LayoutParams n1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        n1.topMargin = UiKit.dp(this, 6);
        card.addView(authNote, n1);
        LinearLayout.LayoutParams k1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        k1.topMargin = UiKit.dp(this, 18);
        card.addView(skip, k1);

        v.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        views.put("auth", v);
        applyAuthMode();
    }

    private void applyAuthMode() {
        if (registerMode) {
            authTitle.setText("Create your account");
            authSubmit.setText("REGISTER");
            authSwitch.setText("Already have an account?  LOGIN");
        } else {
            authTitle.setText("Welcome back");
            authSubmit.setText("LOGIN");
            authSwitch.setText("New here?  CREATE ACCOUNT");
        }
        authNote.setText("");
    }

    private void submitAuth() {
        final String phone = phoneEt.getText().toString().trim();
        final String pin = pinEt.getText().toString().trim();
        if (!phone.matches("^(?:\\+?254|0)(1|7)\\d{8}$")) {
            authNote.setText("Enter a valid Safaricom/Airtel number (07xx / 01xx)");
            return;
        }
        if (pin.length() != 4) {
            authNote.setText("PIN must be exactly 4 numbers");
            return;
        }
        authNote.setText("Please wait…");
        String imei = SimInfo.deviceId(this, store);
        String device = SimInfo.deviceModel();
        Api.Cb cb = (ok, data, err) -> {
            if (ok && data != null && data.optBoolean("ok")) {
                store.setToken(data.optString("token"));
                JSONObject user = data.optJSONObject("user");
                if (user != null && user.optJSONObject("activePackage") != null) {
                    store.setActivePackage(user.optJSONObject("activePackage"));
                }
                authNote.setText("");
                if (pendingBuyPkg != null) {
                    String p = pendingBuyPkg;
                    pendingBuyPkg = null;
                    openPackages();
                    h.postDelayed(() -> buyPackage(p), 350);
                } else {
                    buildMain();
                    show("main");
                    autoConnect();
                }
            } else {
                authNote.setText(err == null ? "Failed" : err);
            }
        };
        if (registerMode) Api.register(phone, pin, imei, device, cb);
        else Api.login(phone, pin, imei, device, cb);
    }

    /* ==================== MAIN ==================== */
    private void buildMain() {
        if (views.get("main") != null) { refreshMainSession(); return; }
        LinearLayout v = UiKit.column(this);

        // header
        LinearLayout header = UiKit.row(this);
        header.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14), UiKit.dp(this, 18), UiKit.dp(this, 6));
        TextView logo = new TextView(this);
        logo.setText("F");
        logo.setTextColor(UiKit.DARK);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(UiKit.gradient(UiKit.ACCENT, UiKit.ACCENT2, UiKit.dp(this, 14)));
        header.addView(logo, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
        LinearLayout titles = UiKit.column(this);
        LinearLayout tr = UiKit.row(this);
        TextView title = UiKit.label(this, "Fridah Tech", UiKit.TXT, 18, true);
        pingTxt = UiKit.label(this, ping > 0 ? "(" + ping + "ms)" : "(--)", UiKit.RED, 12, true);
        tr.addView(title);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = UiKit.dp(this, 8);
        tr.addView(pingTxt, pl);
        TextView sub = UiKit.label(this, "Private tunnel • auto SIM detect", UiKit.MUT, 10, false);
        titles.addView(tr);
        titles.addView(sub);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = UiKit.dp(this, 12);
        header.addView(titles, tl);
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextColor(UiKit.MUT);
        statusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(statusDot);

        v.addView(header);

        // center: power button + status
        FrameLayout center = new FrameLayout(this);
        LinearLayout mid = UiKit.column(this);
        mid.setGravity(Gravity.CENTER);
        power = new PowerButtonView(this);
        power.setOnClickListener(x -> {
            if (TunnelService.currentState.equals(TunnelService.ST_CONNECTED)) stopTunnel();
            else connectTunnel();
        });
        LinearLayout.LayoutParams pw = new LinearLayout.LayoutParams(UiKit.dp(this, 235), UiKit.dp(this, 235));
        mid.addView(power, pw);
        statusTxt = UiKit.label(this, "Disconnected", UiKit.MUT, 20, true);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = UiKit.dp(this, 16);
        mid.addView(statusTxt, sl);
        TextView disconnect = UiKit.ghostButton(this, "DISCONNECT");
        disconnect.setOnClickListener(x -> stopTunnel());
        disconnect.setVisibility(View.GONE);
        disconnect.setTag("disc");
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = UiKit.dp(this, 10);
        mid.addView(disconnect, dl);
        speedTxt = UiKit.label(this, "\u2193 0 B/s    \u2191 0 B/s", 0xFF7FD9FF, 13, false);
        speedTxt.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = UiKit.dp(this, 12);
        mid.addView(speedTxt, sp);
        sessionTxt = new TextView(this);
        sessionTxt.setTextColor(UiKit.TXT);
        sessionTxt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sessionTxt.setTypeface(Typeface.DEFAULT_BOLD);
        sessionTxt.setBackground(UiKit.gradient(0x2200E5FF, 0x227C4DFF, UiKit.dp(this, 20)));
        sessionTxt.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 8), UiKit.dp(this, 16), UiKit.dp(this, 8));
        sessionTxt.setVisibility(View.GONE);
        LinearLayout.LayoutParams se = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        se.topMargin = UiKit.dp(this, 12);
        mid.addView(sessionTxt, se);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.CENTER;
        center.addView(mid, cl);
        v.addView(center, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // bottom bar: Reels | Packages | Support
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(UiKit.dp(this, 24));
        bgd.setColor(0xD910192D);
        bar.setBackground(bgd);
        bar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12), UiKit.dp(this, 12));
        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        TextView reelsB = UiKit.ghostButton(this, "REELS");
        reelsB.setOnClickListener(x -> openReels());
        TextView pkgB = UiKit.button(this, "PACKAGES", UiKit.ACCENT, UiKit.ACCENT2);
        pkgB.setOnClickListener(x -> openPackages());
        TextView suppB = UiKit.ghostButton(this, "SUPPORT");
        suppB.setOnClickListener(x -> openSupport());
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r1.rightMargin = UiKit.dp(this, 6);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
        r2.leftMargin = UiKit.dp(this, 3);
        r2.rightMargin = UiKit.dp(this, 3);
        LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r3.leftMargin = UiKit.dp(this, 6);
        rowB.addView(reelsB, r1);
        rowB.addView(pkgB, r2);
        rowB.addView(suppB, r3);
        bar.addView(rowB);
        v.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        views.put("main", v);
        refreshMainSession();
    }

    private View findTag(View root, Object tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findTag(g.getChildAt(i), tag);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void setPowerState(String st) {
        buildMain();
        if (TunnelService.ST_CONNECTED.equals(st)) {
            power.setState(PowerButtonView.ON);
            statusTxt.setText("Connected");
            statusTxt.setTextColor(UiKit.GREEN);
            statusDot.setTextColor(UiKit.GREEN);
            View d = findTag(views.get("main"), "disc");
            if (d != null) d.setVisibility(View.VISIBLE);
        } else if (TunnelService.ST_CONNECTING.equals(st)) {
            power.setState(PowerButtonView.CONNECTING);
            statusTxt.setText("Connecting…");
            statusTxt.setTextColor(UiKit.ACCENT);
            statusDot.setTextColor(UiKit.ACCENT);
            View d = findTag(views.get("main"), "disc");
            if (d != null) d.setVisibility(View.GONE);
        } else {
            power.setState(PowerButtonView.OFF);
            statusTxt.setText("Disconnected");
            statusTxt.setTextColor(UiKit.MUT);
            statusDot.setTextColor(UiKit.MUT);
            View d = findTag(views.get("main"), "disc");
            if (d != null) d.setVisibility(View.GONE);
            if (speedTxt != null) speedTxt.setText("\u2193 0 B/s    \u2191 0 B/s");
            if (chipTicker != null) { h.removeCallbacks(chipTicker); chipTicker = null; }
            sessionTxt.setVisibility(View.GONE);
            freeMode = false;
        }
    }

    private void updatePing() {
        if (pingTxt != null) pingTxt.setText(ping > 0 ? "(" + ping + "ms)" : "(--)");
    }

    private void refreshMainSession() {
        JSONObject p = store.activePackage();
        if (p != null && p.optLong("expiresAt", 0) > System.currentTimeMillis()) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.US);
            sessionTxt.setText("PREMIUM • " + p.optString("name") + " • until " + sdf.format(new Date(p.optLong("expiresAt"))));
            sessionTxt.setVisibility(View.VISIBLE);
            return;
        }
        if (!freeMode) sessionTxt.setVisibility(View.GONE);
    }

    /* ==================== connection flow ==================== */
    private void autoConnect() {
        if (carrier == null) carrier = SimInfo.carrier(this);
        if (carrier == null) {
            Toast.makeText(this, "SIM not detected — use CONNECT to try anyway", Toast.LENGTH_LONG).show();
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        connectTunnel();
    }

    private void connectTunnel() {
        if (carrier == null) carrier = SimInfo.carrier(this);
        if (carrier == null) {
            dialog("No SIM config", "This app auto-connects on Airtel and Safaricom SIMs.\n\nContact admin 0795314221 for help.", null);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        JSONObject pkg = store.activePackage();
        long premExp = pkg != null ? pkg.optLong("expiresAt", 0) : 0;
        Intent i = new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_CONNECT);
        i.putExtra("carrier", carrier);
        i.putExtra("premiumExpiresAt", premExp);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        buildMain();
        show("main");
        setPowerState(TunnelService.ST_CONNECTING);
    }

    private void stopTunnel() {
        Intent i = new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_DISCONNECT);
        startService(i);
        if (store.token() != null) {
            Api.freenetStop(store.token(), SimInfo.deviceId(this, store), null);
        }
        setPowerState(TunnelService.ST_DISCONNECTED);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) connectTunnel();
            else Toast.makeText(this, "VPN permission is required to connect", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_INSTALL) {
            // installer returned; nothing else needed
        }
    }

    private void onTunnelConnected() {
        setPowerState(TunnelService.ST_CONNECTED);
        // decide free net vs premium via backend (real auth + IMEI registry)
        String token = store.token();
        if (token == null) {
            stopTunnel();
            buildAuth();
            show("auth");
            authNote.setText("Login or register to activate your free net");
            return;
        }
        String imei = SimInfo.deviceId(this, store);
        Api.freenetStart(token, imei, (ok, data, err) -> {
            if (ok && data != null && data.optBoolean("allowed")) {
                startFreeSession(data.optInt("minutes", 20), data.optInt("mbCap", 40),
                        data.optInt("day", 1), data.optInt("daysTotal", 3),
                        data.optBoolean("resumed", false));
            } else if (ok && data != null && !data.optBoolean("allowed")) {
                handleFreeDenied(data.optString("reason", "over"), data.optString("error", "Free net is over. Buy net!"));
            } else {
                // backend unreachable — local IMEI fallback
                localFreeNetFallback();
            }
            refreshPremiumFromServer();
        });
    }

    private void startFreeSession(int minutes, int mb, int day, int daysTotal, boolean resumed) {
        freeMode = true;
        freeEndAt = System.currentTimeMillis() + minutes * 60000L;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        store.markFreeDay(today, System.currentTimeMillis());
        // enforce limits inside the tunnel service too (time + MB cap)
        Intent i = new Intent(this, TunnelService.class).setAction(TunnelService.ACTION_SET_LIMIT);
        i.putExtra("freeMinutes", minutes);
        i.putExtra("freeMb", mb);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        // cool dialog
        String mbMsg = config.optJSONObject("messages") != null
                ? config.optJSONObject("messages").optString("freeNet", "") : "";
        if (mbMsg.isEmpty()) mbMsg = "You have received {mb}MBs free — enjoy!";
        String text = mbMsg.replace("{mb}", String.valueOf(mb))
                + "\n\nYou can use this internet on any other app — WhatsApp, Instagram, TikTok and more."
                + "\n\n" + minutes + " minutes today" + (resumed ? " (resumed)" : "") + " • Day " + day + " of " + daysTotal;
        dialog("Free Internet", text, null);
        startChip(minutes, mb, day, daysTotal);
    }

    private void startChip(int minutes, int mb, int day, int daysTotal) {
        if (chipTicker != null) h.removeCallbacks(chipTicker);
        chipTicker = new Runnable() {
            @Override
            public void run() {
                long left = freeEndAt - System.currentTimeMillis();
                if (left <= 0) return;
                if (sessionTxt != null) {
                    long mm = left / 60000, ss = (left / 1000) % 60;
                    sessionTxt.setText(String.format(Locale.US, "FREE NET • %02d:%02d left • %dMB • Day %d/%d",
                            mm, ss, mb, day, daysTotal));
                    sessionTxt.setVisibility(View.VISIBLE);
                }
                h.postDelayed(this, 1000);
            }
        };
        h.post(chipTicker);
    }

    private void handleFreeDenied(String reason, String message) {
        stopTunnel();
        String m = message == null || message.isEmpty() ? "Free net is over. Buy net!" : message;
        if ("today".equals(reason)) {
            dialog("Free Net Used Today", m + "\n\n1 free session per day on this device — come back tomorrow or buy a package now.", () -> openPackages());
        } else {
            dialog("Free Net Over", m, () -> openPackages());
        }
    }

    private void localFreeNetFallback() {
        int used = store.freeDayCount();
        if (used >= 3) {
            handleFreeDenied("over", "Free net is over. Buy net!");
            return;
        }
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        long started = store.freeDayStart(today);
        long now = System.currentTimeMillis();
        if (started > 0) {
            long elapsed = now - started;
            long remain = 20 * 60000L - elapsed;
            if (remain <= 0) {
                handleFreeDenied("today", "Free net already used today (1 session per day). Come back tomorrow or buy a package.");
            } else {
                startFreeSession((int) (remain / 60000L), 40, used, 3, true);
            }
        } else {
            startFreeSession(20, 40, used + 1, 3, false);
        }
    }

    private void onTunnelError(String msg, int code) {
        setPowerState(TunnelService.ST_DISCONNECTED);
        if (code == 302) {
            boolean buy = msg != null && msg.toLowerCase().contains("buy");
            if (msg == null || msg.isEmpty()) msg = "Please use another sim card";
            dialog("Connection Blocked", msg, buy ? () -> openPackages() : null);
        } else if (code == 2) {
            dialog("Free Net Over", msg == null || msg.isEmpty() ? "Free net is over. Buy net!" : msg, () -> openPackages());
        } else if (code == 4) {
            dialog("Package Ended", msg == null || msg.isEmpty() ? "Your package has ended. Buy net!" : msg, () -> openPackages());
        } else if (msg != null && !msg.isEmpty()) {
            dialog("Could not connect", msg, null);
        }
    }

    private void refreshPremiumFromServer() {
        String token = store.token();
        if (token == null) return;
        Api.me(token, (ok, data, err) -> {
            if (ok && data != null) {
                JSONObject user = data.optJSONObject("user");
                if (user != null) {
                    JSONObject ap = user.optJSONObject("activePackage");
                    if (ap != null) {
                        store.setActivePackage(ap);
                        JSONObject existing = store.activePackage();
                        if (existing == null || ap.optLong("expiresAt", 0) > existing.optLong("expiresAt", 0)) {
                            store.setActivePackage(ap);
                        }
                    }
                    refreshMainSession();
                }
            }
        });
    }

    /* ==================== PACKAGES + PAYMENTS ==================== */
    private void openPackages() {
        buildPackages();
        show("packages");
    }

    private JSONArray packages() {
        JSONArray arr = config.optJSONArray("packages");
        if (arr != null && arr.length() > 0) return arr;
        JSONArray def = new JSONArray();
        try {
            String[][] d = {{"p1", "1 Hour Unlimited", "1", "11", ""}, {"p2", "3 Hours Unlimited", "3", "23", ""},
                    {"p3", "6 Hours Unlimited", "6", "55", "MOST POPULAR"}, {"p4", "12 Hours Unlimited", "12", "88", "BEST VALUE"},
                    {"p5", "1 Day Unlimited", "24", "100", ""}};
            for (String[] x : d) {
                JSONObject o = new JSONObject();
                o.put("id", x[0]);
                o.put("name", x[1]);
                o.put("hours", Integer.parseInt(x[2]));
                o.put("price", Integer.parseInt(x[3]));
                o.put("badge", x[4]);
                def.put(o);
            }
        } catch (Exception ignored) { }
        return def;
    }

    private void buildPackages() {
        if (views.get("packages") != null && pkgList != null) { fillPackages(); return; }
        LinearLayout v = UiKit.column(this);
        LinearLayout head = UiKit.row(this);
        head.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14), UiKit.dp(this, 18), UiKit.dp(this, 6));
        TextView back = UiKit.label(this, "‹", UiKit.TXT, 26, true);
        back.setOnClickListener(x -> show("main"));
        head.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
        LinearLayout titles = UiKit.column(this);
        titles.addView(UiKit.label(this, "Fridah Online Net", UiKit.TXT, 19, true));
        titles.addView(UiKit.label(this, "WiFi bundles • pay with M-PESA STK push", UiKit.MUT, 11, false));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = UiKit.dp(this, 12);
        head.addView(titles, tl);
        v.addView(head);

        LinearLayout scrollWrap = UiKit.column(this);
        scrollWrap.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 10), UiKit.dp(this, 16), UiKit.dp(this, 10));
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        pkgList = UiKit.column(this);
        sc.addView(pkgList);
        scrollWrap.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.addView(scrollWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = UiKit.label(this, "Payments are instant and confirmed live. Questions? WhatsApp 0795314221", UiKit.MUT, 11, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, 0, 0, UiKit.dp(this, 14));
        v.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        views.put("packages", v);
        fillPackages();
    }

    private void fillPackages() {
        if (pkgList == null) return;
        pkgList.removeAllViews();
        JSONArray arr = packages();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(UiKit.panel(this, 20));
            card.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16), UiKit.dp(this, 18), UiKit.dp(this, 16));
            LinearLayout left = UiKit.column(this);
            TextView name = UiKit.label(this, p.optString("name", "Bundle"), UiKit.TXT, 16, true);
            TextView sub = UiKit.label(this, "Unlimited data • " + p.optInt("hours", 1) + "h", UiKit.MUT, 12, false);
            left.addView(name);
            left.addView(sub);
            card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView badge = null;
            String bd = p.optString("badge", "");
            if (bd != null && !bd.isEmpty()) {
                badge = UiKit.label(this, bd, 0xFFFFB74D, 10, true);
            }
            TextView price = UiKit.label(this, "KES " + p.optInt("price", 0), UiKit.ACCENT, 18, true);
            TextView buy = UiKit.button(this, "BUY", UiKit.ACCENT, UiKit.ACCENT2);
            buy.setOnClickListener(x -> buyPackage(p.optString("id")));
            LinearLayout right = UiKit.column(this);
            right.setGravity(Gravity.END);
            right.addView(price);
            if (badge != null) right.addView(badge);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = UiKit.dp(this, 8);
            right.addView(buy, bl);
            card.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = UiKit.dp(this, 12);
            pkgList.addView(card, cl);
        }
    }

    private void buyPackage(String packageId) {
        if (store.token() == null) {
            pendingBuyPkg = packageId;
            buildAuth();
            show("auth");
            authNote.setText("Login or register first to buy a package");
            return;
        }
        JSONObject p = null;
        JSONArray arr = packages();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            if (x != null && packageId.equals(x.optString("id"))) { p = x; break; }
        }
        if (p == null) return;
        final JSONObject pkg = p;
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        LinearLayout box = UiKit.column(this);
        int pad = UiKit.dp(this, 24);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(UiKit.panel(this, 24));
        box.addView(UiKit.label(this, pkg.optString("name", "Bundle"), UiKit.TXT, 20, true));
        TextView price = UiKit.label(this, "KES " + pkg.optInt("price", 0) + " • Unlimited • " + pkg.optInt("hours", 1) + "h", UiKit.MUT, 13, false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = UiKit.dp(this, 6);
        box.addView(price, pl);
        paySpinner = new ProgressBar(this);
        LinearLayout sp = new LinearLayout(this);
        sp.setGravity(Gravity.CENTER);
        sp.addView(paySpinner);
        paySpinner.setVisibility(View.GONE);
        payStatus = UiKit.label(this, "We will send an M-PESA STK push to your phone. Enter your PIN to pay.", UiKit.MUT, 13, false);
        payStatus.setLineSpacing(UiKit.dp(this, 2), 1f);
        LinearLayout.LayoutParams st = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        st.topMargin = UiKit.dp(this, 14);
        box.addView(payStatus, st);
        payRetry = UiKit.button(this, "RETRY", UiKit.ACCENT, UiKit.ACCENT2);
        payRetry.setVisibility(View.GONE);
        payRetry.setOnClickListener(x -> initiatePay(pkg));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = UiKit.dp(this, 14);
        box.addView(payRetry, rl);
        TextView cancel = UiKit.label(this, "Cancel", UiKit.MUT, 13, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(x -> closePayDialog());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = UiKit.dp(this, 10);
        box.addView(cancel, cl);
        ab.setView(box);
        ab.setCancelable(true);
        payDialog = ab.create();
        payDialog.setOnDismissListener(d -> closePayDialog());
        payDialog.show();
        initiatePay(pkg);
    }

    private void initiatePay(JSONObject pkg) {
        paySpinner.setVisibility(View.VISIBLE);
        payRetry.setVisibility(View.GONE);
        payStatus.setTextColor(UiKit.MUT);
        payStatus.setText("Sending STK push…");
        Api.payInitiate(store.token(), pkg.optString("id"), "", (ok, data, err) -> {
            paySpinner.setVisibility(View.GONE);
            if (ok && data != null && data.optBoolean("ok")) {
                payId = data.optString("paymentId");
                payStatus.setText(data.optString("message", "STK push sent. Enter your M-PESA PIN."));
                subscribePay(pkg);
            } else {
                payStatus.setTextColor(UiKit.RED);
                payStatus.setText(err == null ? "Could not start payment" : err);
                payRetry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void subscribePay(JSONObject pkg) {
        if (payStream != null) payStream.stop();
        payPolling = true;
        payStream = Api.payStream(store.token(), payId, (event, data) -> {
            if (payDialog == null || !payDialog.isShowing()) { payPolling = false; return; }
            if ("payment".equals(event) && data != null) {
                String st = data.optString("status");
                if ("completed".equals(st)) {
                    payPolling = false;
                    String receipt = data.optString("receipt", "");
                    payStatus.setTextColor(UiKit.GREEN);
                    payStatus.setText("Payment received!" + (receipt.isEmpty() ? "" : "\nM-PESA receipt: " + receipt)
                            + "\n" + pkg.optString("name") + " activated. Enjoy unlimited net!");
                    JSONObject ap = data.optJSONObject("activePackage");
                    if (ap != null) store.setActivePackage(ap);
                    refreshPremiumFromServer();
                    h.postDelayed(() -> { closePayDialog(); refreshMainSession(); }, 1800);
                } else if ("failed".equals(st)) {
                    payPolling = false;
                    payStatus.setTextColor(UiKit.RED);
                    payStatus.setText("Payment failed or cancelled. Retry to pay again.");
                    payRetry.setVisibility(View.VISIBLE);
                }
            } else if ("closed".equals(event) && payPolling) {
                startPayPolling(pkg);
            }
        });
    }

    private void startPayPolling(JSONObject pkg) {
        h.postDelayed(() -> {
            if (!payPolling || payDialog == null || !payDialog.isShowing()) return;
            Api.payStatus(store.token(), payId, (ok, data, err) -> {
                if (!ok || data == null) { if (payPolling) startPayPolling(pkg); return; }
                String st = data.optString("status");
                if ("completed".equals(st)) {
                    payStatus.setTextColor(UiKit.GREEN);
                    payStatus.setText("Payment received!\n" + pkg.optString("name") + " activated. Enjoy unlimited net!");
                    JSONObject ap = data.optJSONObject("activePackage");
                    if (ap != null) store.setActivePackage(ap);
                    refreshPremiumFromServer();
                    payPolling = false;
                    h.postDelayed(() -> { closePayDialog(); refreshMainSession(); }, 1800);
                } else if ("failed".equals(st)) {
                    payPolling = false;
                    payStatus.setTextColor(UiKit.RED);
                    payStatus.setText("Payment failed or cancelled. Retry to pay again.");
                    payRetry.setVisibility(View.VISIBLE);
                } else if (payPolling) {
                    startPayPolling(pkg);
                }
            });
        }, 4000);
    }

    private void closePayDialog() {
        payPolling = false;
        if (payStream != null) { payStream.stop(); payStream = null; }
        if (payDialog != null) { try { payDialog.dismiss(); } catch (Exception ignored) { } payDialog = null; }
    }

    /* ==================== REELS ==================== */
    private void openReels() {
        if (views.get("reels") == null) {
            LinearLayout v = UiKit.column(this);
            LinearLayout head = UiKit.row(this);
            head.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 14), UiKit.dp(this, 18), UiKit.dp(this, 6));
            TextView back = UiKit.label(this, "‹", UiKit.TXT, 26, true);
            back.setOnClickListener(x -> show("main"));
            head.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
            LinearLayout titles = UiKit.column(this);
            titles.addView(UiKit.label(this, "Reels", UiKit.TXT, 19, true));
            titles.addView(UiKit.label(this, "New user trial • free videos while you relax", UiKit.MUT, 11, false));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.leftMargin = UiKit.dp(this, 12);
            head.addView(titles, tl);
            v.addView(head);
            reelsWv = new WebView(this);
            WebSettings ws = reelsWv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            if (Build.VERSION.SDK_INT >= 21) ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            reelsWv.setWebViewClient(new WebViewClient());
            reelsWv.setBackgroundColor(0xFF080D18);
            v.addView(reelsWv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            views.put("reels", v);
        }
        loadReels();
        show("reels");
    }

    private void loadReels() {
        JSONArray reels = config.optJSONArray("reels");
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<style>body{background:#080d18;color:#e9efff;font-family:sans-serif;padding:14px}")
                .append("iframe{width:100%;height:520px;border:0;border-radius:16px;margin-bottom:18px;background:#0d1424}")
                .append("h2{font-size:17px;color:#00e5ff;margin:4px 0 12px 0}</style></head><body>")
                .append("<h2>Reels</h2>");
        boolean any = false;
        if (reels != null) {
            for (int i = 0; i < reels.length(); i++) {
                String url = reels.optString(i, "");
                if (url.isEmpty()) continue;
                try {
                    String enc = URLEncoder.encode(url, "UTF-8");
                    sb.append("<iframe src='https://www.facebook.com/plugins/video.php?height=520&href=")
                            .append(enc).append("&show_text=false&width=340&t=0&appId=' scrolling='no' allowfullscreen></iframe>");
                    any = true;
                } catch (Exception ignored) { }
            }
        }
        if (!any) sb.append("<p>No reels yet — check back soon.</p>");
        sb.append("</body></html>");
        if (reelsWv != null) reelsWv.loadDataWithBaseURL("https://www.facebook.com", sb.toString(), "text/html", "utf-8", null);
    }

    /* ==================== SUPPORT ==================== */
    private void openSupport() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/254795314221"));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Open WhatsApp: 0795314221", Toast.LENGTH_LONG).show();
        }
    }

    /* ==================== MAINTENANCE ==================== */
    private void buildMaintenance(String msg) {
        if (views.get("maint") != null) return;
        LinearLayout v = UiKit.column(this);
        v.setGravity(Gravity.CENTER);
        int pad = UiKit.dp(this, 26);
        v.setPadding(pad, pad, pad, pad);
        TextView gear = UiKit.label(this, "⚙", 0xFF7FA7D9, 52, false);
        TextView t = UiKit.label(this, "Under Maintenance", UiKit.TXT, 22, true);
        TextView m = UiKit.label(this, msg, UiKit.MUT, 14, false);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(UiKit.dp(this, 3), 1f);
        TextView retry = UiKit.button(this, "TRY AGAIN", UiKit.ACCENT, UiKit.ACCENT2);
        retry.setOnClickListener(x -> {
            show("splash");
            Api.config((ok, data, err) -> {
                if (ok && data != null) {
                    config = data;
                    if (data.optJSONObject("maintenance") != null && data.optJSONObject("maintenance").optBoolean("on")) {
                        show("maint");
                    } else {
                        finishSplash();
                    }
                } else show("maint");
            });
        });
        v.addView(gear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l1.topMargin = UiKit.dp(this, 10);
        v.addView(t, l1);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l2.topMargin = UiKit.dp(this, 10);
        v.addView(m, l2);
        LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l3.topMargin = UiKit.dp(this, 26);
        v.addView(retry, l3);
        views.put("maint", v);
    }

    /* ==================== UPDATES ==================== */
    private void checkForUpdate() {
        JSONObject upd = config.optJSONObject("update");
        if (upd == null) return;
        int code = upd.optInt("code", 0);
        final String apkUrl = upd.optString("apkUrl", "");
        final String name = upd.optString("name", "");
        if (code > APP_VERSION_CODE && !apkUrl.isEmpty()) {
            h.post(() -> {
                AlertDialog.Builder ab = new AlertDialog.Builder(this);
                LinearLayout box = UiKit.column(this);
                int pad = UiKit.dp(this, 24);
                box.setPadding(pad, pad, pad, pad);
                box.setBackground(UiKit.panel(this, 24));
                box.addView(UiKit.label(this, "New version available", UiKit.TXT, 20, true));
                TextView body = UiKit.label(this, "Fridah Tech v" + name + " is ready.\nUpdate now for the best experience.", UiKit.MUT, 14, false);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bl.topMargin = UiKit.dp(this, 10);
                box.addView(body, bl);
                TextView go = UiKit.button(this, "UPDATE NOW", UiKit.GREEN, UiKit.ACCENT);
                final AlertDialog ud = ab.create();
                go.setOnClickListener(x -> {
                    try { ud.dismiss(); } catch (Exception ignored) { }
                    downloadUpdate(apkUrl);
                });
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gl.topMargin = UiKit.dp(this, 18);
                box.addView(go, gl);
                ab.setView(box);
                ud.show();
            });
        }
    }

    private void downloadUpdate(String apkUrl) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Intent si = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(si, REQ_INSTALL);
                return;
            }
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
            req.setTitle("Fridah Tech " + APP_VERSION_NAME);
            req.setDescription("Downloading update…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setMimeType("application/vnd.android.package-archive");
            long id = dm.enqueue(req);
            Toast.makeText(this, "Downloading update…", Toast.LENGTH_LONG).show();
            pollDownload(dm, id);
        } catch (Exception e) {
            Toast.makeText(this, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pollDownload(final DownloadManager dm, final long id) {
        h.postDelayed(() -> {
            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(id);
            android.database.Cursor c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Uri uri = dm.getUriForDownloadedFile(id);
                    Intent open = new Intent(Intent.ACTION_VIEW);
                    open.setDataAndType(uri, "application/vnd.android.package-archive");
                    open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(open);
                    } catch (Exception e) {
                        Toast.makeText(this, "Open the downloaded APK to install", Toast.LENGTH_LONG).show();
                    }
                    return;
                } else if (status == DownloadManager.STATUS_FAILED) {
                    Toast.makeText(this, "Download failed — try again later", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (c != null) c.close();
            pollDownload(dm, id);
        }, 1500);
    }

    /* ==================== misc ==================== */
    private void requestPerms() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        perms.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMS) {
            carrier = SimInfo.carrier(this);
        }
    }

    private void show(String name) {
        View v = views.get(name);
        if (v == null) return;
        screens.removeAllViews();
        screens.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void dialog(String title, String msg, Runnable after) {
        h.post(() -> {
            AlertDialog.Builder ab = new AlertDialog.Builder(this);
            LinearLayout box = UiKit.column(this);
            int pad = UiKit.dp(this, 24);
            box.setPadding(pad, pad, pad, pad);
            box.setBackground(UiKit.panel(this, 24));
            box.addView(UiKit.label(this, title, UiKit.TXT, 20, true));
            TextView m = UiKit.label(this, msg, UiKit.MUT, 14, false);
            m.setLineSpacing(UiKit.dp(this, 2), 1f);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ml.topMargin = UiKit.dp(this, 12);
            box.addView(m, ml);
            TextView ok = UiKit.button(this, after == null ? "OK" : "BUY NET", UiKit.ACCENT, UiKit.ACCENT2);
            final AlertDialog dlg = ab.create();
            ok.setOnClickListener(x -> {
                try { dlg.dismiss(); } catch (Exception ignored) { }
                if (after != null) after.run();
            });
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ol.topMargin = UiKit.dp(this, 18);
            box.addView(ok, ol);
            dlg.show();
        });
    }

    @Override
    public void onBackPressed() {
        if (views.get("packages") != null && screens.getChildAt(0) == views.get("packages")) show("main");
        else if (views.get("reels") != null && screens.getChildAt(0) == views.get("reels")) show("main");
        else if (views.get("welcome") != null && screens.getChildAt(0) == views.get("welcome")) {
            // allow reading the welcome card; Next is the way forward
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        if (payStream != null) payStream.stop();
        super.onDestroy();
    }
}
