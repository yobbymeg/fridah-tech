package com.fridah.tech;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.security.MessageDigest;

/** SIM carrier detection (Airtel / Safaricom Kenya) + stable device id (IMEI when readable). */
public class SimInfo {

    public static String carrier(Context c) {
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;
            String[] names = new String[]{tm.getNetworkOperatorName(), tm.getSimOperatorName()};
            for (String n : names) {
                if (n == null) continue;
                String s = n.toLowerCase();
                if (s.contains("airtel")) return "airtel";
                if (s.contains("safaricom")) return "safaricom";
            }
            // mcc+mnc codes: Safaricom KE 63902, Airtel KE 63903 (also 63905 Airtel/Telkom data)
            String[] codes = new String[]{tm.getNetworkOperator(), tm.getSimOperator()};
            for (String code : codes) {
                if ("63902".equals(code) || "63907".equals(code)) return "safaricom";
                if ("63903".equals(code) || "63905".equals(code)) return "airtel";
            }
            // dual sim: check every subscription
            if (checkSelfPermission(c)) {
                SubscriptionManager sm = (SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (sm != null) {
                    for (SubscriptionInfo si : sm.getActiveSubscriptionInfoList()) {
                        String n = si == null ? null : si.getDisplayName() == null ? "" : si.getDisplayName().toString().toLowerCase();
                        if (n == null) continue;
                        if (n.contains("airtel")) return "airtel";
                        if (n.contains("safaricom")) return "safaricom";
                        int mcc = si.getMcc() * 100 + si.getMnc();
                        if (mcc == 63902 || mcc == 63907) return "safaricom";
                        if (mcc == 63903 || mcc == 63905) return "airtel";
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean checkSelfPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Real IMEI when the platform allows; otherwise a stable device fingerprint. */
    public static String deviceId(Context c, Store store) {
        // prefer cached one only if no real imei accessible
        try {
            if (checkSelfPermission(c)) {
                TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
                String imei = null;
                if (tm != null) {
                    if (Build.VERSION.SDK_INT >= 26) imei = tm.getImei();
                    else if (Build.VERSION.SDK_INT >= 24) imei = tm.getDeviceId(0);
                    else imei = tm.getDeviceId();
                }
                if (imei != null && imei.matches("[0-9]{14,17}")) {
                    store.setImei(imei);
                    return imei;
                }
            }
        } catch (Throwable ignored) { }
        String cached = store.imei();
        if (cached != null && cached.matches("[0-9]{14,17}")) return cached;
        String id = "F" + sha(cached != null ? cached : (Settings.Secure.ANDROID_ID + Build.MODEL)).substring(0, 14).toUpperCase();
        store.setImei(id);
        return id;
    }

    public static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    public static String deviceModel() {
        String m = Build.MANUFACTURER + " " + Build.MODEL;
        return m.length() > 40 ? m.substring(0, 40) : m;
    }
}
