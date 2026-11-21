package com.fridah.tech;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Local persistence: token, device id, free-net days cache, active package, settings. */
public class Store {

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences("fridah_tech", Context.MODE_PRIVATE);
    }

    public String token() { return p.getString("token", null); }
    public void setToken(String t) { p.edit().putString("token", t).apply(); }

    public String imei() { return p.getString("imei", null); }
    public void setImei(String v) { p.edit().putString("imei", v).apply(); }

    public boolean welcomeShown() { return p.getBoolean("welcome", false); }
    public void setWelcomeShown() { p.edit().putBoolean("welcome", true).apply(); }

    public int lastPing() { return p.getInt("ping", 0); }
    public void setLastPing(int ms) { p.edit().putInt("ping", ms).apply(); }

    /** free-net days used locally (fallback when backend unreachable): date -> startedAt */
    public long freeDayStart(String date) { return p.getLong("fd_" + date, 0); }
    public void markFreeDay(String date, long startedAt) {
        java.util.Set<String> days = p.getStringSet("freeDays", new java.util.HashSet<String>());
        java.util.Set<String> copy = new java.util.HashSet<>(days);
        copy.add(date);
        p.edit().putStringSet("freeDays", copy).putLong("fd_" + date, startedAt).apply();
    }
    public int freeDayCount() { return p.getStringSet("freeDays", new java.util.HashSet<String>()).size(); }

    /** active package json: {packageId,name,hours,price,expiresAt} */
    public JSONObject activePackage() {
        try { return new JSONObject(p.getString("pkg", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    public void setActivePackage(JSONObject o) { p.edit().putString("pkg", o.toString()).apply(); }
    public boolean hasLivePackage() {
        JSONObject o = activePackage();
        return o.optLong("expiresAt", 0) > System.currentTimeMillis();
    }
}
