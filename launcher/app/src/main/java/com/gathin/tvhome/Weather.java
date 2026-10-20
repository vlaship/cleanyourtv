package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Current weather for the header (config.xml show_weather). The place comes from Android's own location
 * (network / fused provider, when Location is on in the TV's settings), otherwise from the public IP (ipapi.co,
 * falling back to ipwho.is), once a day; the weather from Open-Meteo every 30 minutes.
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
                String text = fetch(app, p);
                if (text != null) {
                    p.edit().putString("text", text).putLong("time", System.currentTimeMillis()).apply();
                    MAIN.post(() -> listener.onWeather(text));
                }
            } finally {
                running = false;
            }
        });
    }

    private static String fetch(Context ctx, SharedPreferences p) {
        try {
            boolean stale = System.currentTimeMillis() - p.getLong("locTime", 0) > LOCATION_MS || !p.contains("lat");
            // A place found by IP is replaced as soon as the TV's own location becomes available.
            boolean upgrade = !"device".equals(p.getString("src", "")) && locationOn(ctx);
            if (stale || upgrade) {
                boolean found = locateDevice(ctx, p);
                if (!found && stale) found = locate(p, "https://ipapi.co/json/") || locate(p, "https://ipwho.is/");
                if (!found && !p.contains("lat")) return null;
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
                    .putString("src", "ip")
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean locationOn(Context ctx) {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        return lm != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || lm.isLocationEnabled());
    }

    /** Android's own location (Wi-Fi based on a TV) and its town; false when Location is off or does not answer. */
    @SuppressWarnings("MissingPermission")   // checked in locationOn()
    private static boolean locateDevice(Context ctx, SharedPreferences p) {
        try {
            if (!locationOn(ctx)) return false;
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            Location loc = null;
            for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER}) {
                if (!lm.getAllProviders().contains(provider) || !lm.isProviderEnabled(provider)) continue;
                Location last = lm.getLastKnownLocation(provider);
                if (last != null && System.currentTimeMillis() - last.getTime() < LOCATION_MS) {
                    loc = last;
                    break;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    CountDownLatch done = new CountDownLatch(1);
                    Location[] got = new Location[1];
                    lm.getCurrentLocation(provider, null, IO_CALLBACKS, l -> {
                        got[0] = l;
                        done.countDown();
                    });
                    done.await(20, TimeUnit.SECONDS);
                    if (got[0] != null) {
                        loc = got[0];
                        break;
                    }
                }
            }
            if (loc == null) return false;
            String city = "";
            try {
                List<Address> found = new Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                if (found != null && !found.isEmpty()) {
                    Address a = found.get(0);
                    city = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea() != null ? a.getSubAdminArea() : "";
                }
            } catch (Exception ignored) {
                // No town name: the weather still shows, without it.
            }
            p.edit()
                    .putLong("lat", Double.doubleToLongBits(loc.getLatitude()))
                    .putLong("lon", Double.doubleToLongBits(loc.getLongitude()))
                    .putString("city", Cards.cleanLabel(city, ""))
                    .putLong("locTime", System.currentTimeMillis())
                    .putString("src", "device")
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** getCurrentLocation delivers here, not on the IO thread that is waiting for it. */
    private static final java.util.concurrent.Executor IO_CALLBACKS = Executors.newSingleThreadExecutor();

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
