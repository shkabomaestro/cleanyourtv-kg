package local.cleanyourtv.radio;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the stick on the strongest saved Wi-Fi network in range, while a station plays. Android alone joins a network
 * once and stays on it, even a weak 5 GHz one at -75 dBm with a strong 2.4 GHz one beside it, and after a drop it may
 * pick the weak one again. This looks every few seconds and moves only when it is worth a few seconds' break:
 *
 * - the current network is weak (below -65 dBm) and another saved one is at least 10 dB stronger, or
 * - the current network lost its router and the internet, or has had no internet for 20 s: it is then avoided for
 *   ten minutes, and any other saved network in range is better.
 *
 * At most one move in three minutes, so two networks of about the same strength never trade the stick back and forth.
 * Needs the location permission and location switched on (Android's rule for reading the networks in range); without
 * them it sees nothing and does nothing, and Android chooses as before. Called on the radio's link thread only.
 */
final class WifiChooser {
    static final int WEAK_DBM = -65, BETTER_BY_DB = 10, MIN_DBM = -80;
    /** pick(): stay on this network, but join its stronger access point or band. */
    static final int SAME_NETWORK = -2;
    static final long MOVE_GAP_MS = 180_000, AVOID_MS = 600_000, SCAN_GAP_MS = 30_000, FRESH_MS = 120_000;

    private final Map<Integer, Long> avoidUntil = new HashMap<>();
    private long lastMove = -MOVE_GAP_MS, lastScan = -SCAN_GAP_MS;

    /** The network the stick is on now stays out of the choice for ten minutes. */
    void avoidCurrent(WifiManager wifi) {
        WifiInfo info = wifi.getConnectionInfo();
        if (info != null && info.getNetworkId() != -1) avoidUntil.put(info.getNetworkId(), SystemClock.elapsedRealtime() + AVOID_MS);
    }

    /**
     * Joins the strongest saved network in range when that is clearly better than staying. leaving: the current one
     * is no good, so any other in range will do. True when it asked Android to move.
     */
    @SuppressWarnings("deprecation")   // getConfiguredNetworks, enableNetwork: still work below targetSdk 29
    boolean switchToBest(WifiManager wifi, boolean leaving) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMove < MOVE_GAP_MS) return false;
        WifiInfo info = wifi.getConnectionInfo();
        int current = info == null ? -1 : info.getNetworkId();
        int rssi = info == null ? -127 : info.getRssi();
        if (!leaving && current != -1 && rssi >= WEAK_DBM && !avoided(current, now)) return false;   // strong: stay
        List<ScanResult> seen;
        List<WifiConfiguration> saved;
        try {
            seen = wifi.getScanResults();
            saved = wifi.getConfiguredNetworks();
        } catch (SecurityException e) {
            return false;   // no location permission or location off: Android decides alone
        }
        if (seen == null || saved == null || saved.size() < 2) return false;
        Map<String, Integer> idBySsid = new HashMap<>();
        for (WifiConfiguration c : saved) {
            if (c.SSID != null) idBySsid.put(unquote(c.SSID), c.networkId);
        }
        int best = pick(seen, idBySsid, current, rssi, leaving, now);
        if (best == SAME_NETWORK) {
            // The same network is much stronger on its other band or access point (2.4 GHz at -44 beside 5 GHz at -75):
            // join it again, and the strongest one answers.
            android.util.Log.i("radio", "Wi-Fi: rejoining network " + current + " at " + rssi + " dBm, a stronger access point is in range");
            lastMove = now;
            return wifi.reassociate();
        }
        if (best == -1) {
            if (now - lastScan >= SCAN_GAP_MS) {   // nothing fresh enough: look again for the next check
                lastScan = now;
                try {
                    wifi.startScan();
                } catch (SecurityException ignored) {
                    // the next check sees whatever Android's own scans found
                }
            }
            return false;
        }
        android.util.Log.i("radio", "Wi-Fi: moving from network " + current + " (" + rssi + " dBm) to " + best);
        lastMove = now;
        return wifi.enableNetwork(best, true);
    }

    /** The saved network to join, SAME_NETWORK to rejoin this one, or -1 to stay. Pure, for the test. */
    int pick(List<ScanResult> seen, Map<String, Integer> idBySsid, int current, int rssi, boolean leaving, long now) {
        long nowMicros = now * 1000;
        Map<Integer, Integer> strongest = new HashMap<>();
        for (ScanResult r : seen) {
            Integer id = r.SSID == null ? null : idBySsid.get(r.SSID);
            if (id == null || r.level < MIN_DBM) continue;
            if (r.timestamp > 0 && nowMicros - r.timestamp > FRESH_MS * 1000) continue;   // an old sighting
            Integer had = strongest.get(id);
            if (had == null || r.level > had) strongest.put(id, r.level);
        }
        int best = -1, bestLevel = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> e : strongest.entrySet()) {
            int id = e.getKey();
            if (id == current || avoided(id, now)) continue;
            if (e.getValue() > bestLevel) {
                best = id;
                bestLevel = e.getValue();
            }
        }
        boolean currentBad = leaving || current == -1 || avoided(current, now);
        if (best != -1 && (currentBad || bestLevel >= rssi + BETTER_BY_DB)) return best;
        Integer here = strongest.get(current);
        if (!currentBad && here != null && here >= rssi + BETTER_BY_DB) return SAME_NETWORK;
        return -1;
    }

    private boolean avoided(int id, long now) {
        Long until = avoidUntil.get(id);
        return until != null && now < until;
    }

    static String unquote(String ssid) {
        return ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
    }
}
