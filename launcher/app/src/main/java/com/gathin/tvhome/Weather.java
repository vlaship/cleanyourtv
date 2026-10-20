package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the header (config.xml show_weather). The TV has no GPS, so the place comes from the
 * public IP (ipapi.co, falling back to ipwho.is) once a day, and the weather from Open-Meteo every 30 minutes.
 * Neither needs an API key. Everything runs on one background thread; the text is cached so Home shows the
 * last reading at once and a failed request keeps it.
 */
final class Weather {
    interface Listener {
        void onWeather(String text);
    }

    private static final String PREFS = "weather";
    private static final long WEATHER_MS = 30 * 60_000L, LOCATION_MS = 24 * 60 * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private Weather() { }

    /** The cached text right away (if any), then a fresh one when the cache is older than 30 minutes. */
    static void refresh(Context ctx, Listener listener) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cached = p.getString("text", null);
        if (cached != null) listener.onWeather(cached);
        if (System.currentTimeMillis() - p.getLong("time", 0) < WEATHER_MS || running) return;
        running = true;
        IO.execute(() -> {
            try {
                String text = fetch(p);
                if (text != null) {
                    p.edit().putString("text", text).putLong("time", System.currentTimeMillis()).apply();
                    MAIN.post(() -> listener.onWeather(text));
                }
            } finally {
                running = false;
            }
        });
    }

    private static String fetch(SharedPreferences p) {
        try {
            if (System.currentTimeMillis() - p.getLong("locTime", 0) > LOCATION_MS || !p.contains("lat")) {
                if (!locate(p, "https://ipapi.co/json/") && !locate(p, "https://ipwho.is/") && !p.contains("lat")) {
                    return null;
                }
            }
            double lat = Double.longBitsToDouble(p.getLong("lat", 0)), lon = Double.longBitsToDouble(p.getLong("lon", 0));
            JSONObject json = new JSONObject(get(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                            + "&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit", lat, lon)));
            JSONObject cur = json.getJSONObject("current");
            double f = cur.getDouble("temperature_2m");
            long c = Math.round((f - 32) * 5 / 9);
            String icon = icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
            String city = p.getString("city", "");
            return icon + "  " + Math.round(f) + "°F / " + c + "°C" + (city.isEmpty() ? "" : "  ·  " + city);
        } catch (Exception e) {
            return null;
        }
    }

    /** Stores latitude, longitude and city from an IP lookup service; false when it did not answer usefully. */
    private static boolean locate(SharedPreferences p, String url) {
        try {
            JSONObject json = new JSONObject(get(url));
            if (!json.has("latitude") || !json.has("longitude")) return false;
            p.edit()
                    .putLong("lat", Double.doubleToLongBits(json.getDouble("latitude")))
                    .putLong("lon", Double.doubleToLongBits(json.getDouble("longitude")))
                    .putString("city", Cards.cleanLabel(json.optString("city", ""), ""))
                    .putLong("locTime", System.currentTimeMillis())
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "CleanYourTV-launcher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather code to a symbol. */
    private static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";               // clear: sun / moon
        if (code <= 2) return day ? "⛅" : "☁️";                      // partly cloudy
        if (code == 3) return "☁️";                                        // overcast
        if (code == 45 || code == 48) return "🌫️";                   // fog
        if (code >= 51 && code <= 57) return "🌦️";                   // drizzle
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️"; // rain
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";         // snow
        if (code >= 95) return "⛈️";                                       // thunderstorm
        return "☁️";
    }
}
