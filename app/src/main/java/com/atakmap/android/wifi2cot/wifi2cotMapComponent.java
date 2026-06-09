
package com.atakmap.android.wifi2cot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import com.atakmap.coremap.log.Log;
import com.atakmap.android.wifi2cot.plugin.R;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class wifi2cotMapComponent extends DropDownMapComponent {

    private static final String TAG = "wifi2cotMapComponent";

    private MapView mapView;

    private wifi2cotDropDownReceiver ddr;

    private WifiManager wifiManager;
    private BroadcastReceiver wifiScanReceiver;

    // Single worker so scan callbacks are processed in order off the UI thread
    // (the old code spawned a fresh Thread per callback -> races on `nodes`).
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // BSSID -> observations. Concurrent map + copy-on-write lists so the worker
    // thread can append while the UI thread reads in compute() without locking.
    private static final Map<String, List<Sample>> nodes = new ConcurrentHashMap<>();

    public void onCreate(final Context context, Intent intent,
            final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        mapView = view;

        ddr = new wifi2cotDropDownReceiver(view, context, this);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(wifi2cotDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean success = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, false);
                Log.d(TAG, "wifiScanReceiver: " + success);
                if (success) {
                    scanSuccess();
                } else {
                    scanFailure();
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.getApplicationContext().registerReceiver(wifiScanReceiver, intentFilter);
    }

    private void scanSuccess() {

        if (!ddr.isScanning()) {
            Log.d(TAG, "Not in scanning mode");
            return;
        }
        if (wifiManager == null)
            return;

        // Snapshot the observer position ONCE per scan batch (it is constant for
        // the batch) and bail early if we have no fix.
        Marker self = mapView.getSelfMarker();
        GeoPoint here = (self != null) ? self.getPoint() : null;
        if (here == null || !here.isValid()
                || (Math.abs(here.getLatitude()) < 1e-9
                        && Math.abs(here.getLongitude()) < 1e-9)) {
            Log.d(TAG, "No GPS fix; dropping scan batch");
            return;
        }

        final double lat = here.getLatitude();
        final double lng = here.getLongitude();
        final double alt = here.isAltitudeValid()
                ? here.getAltitude() : Double.NaN;
        final long now = System.currentTimeMillis();

        final List<ScanResult> results = wifiManager.getScanResults();

        worker.execute(() -> {
            for (ScanResult s : results) {
                if (s.BSSID == null)
                    continue; // skip malformed result, keep processing the rest

                Log.d(TAG, "Scan result: BSSID: " + s.BSSID + " SSID: " + s.SSID
                        + " RSSI: " + s.level + " Freq: " + s.frequency);

                Sample sample = new Sample(s.level, s.frequency, lat, lng, alt,
                        s.BSSID, s.SSID == null ? "" : s.SSID, now);

                // computeIfAbsent + COW list = safe concurrent append.
                nodes.computeIfAbsent(s.BSSID,
                        k -> new CopyOnWriteArrayList<>()).add(sample);
            }
        });
    }

    private void scanFailure() {
        // A failed scan just means the OS returned cached results; do not ingest
        // them (their position would be wrong) and do not spam errors.
        Log.d(TAG, "Scan failed (throttled or no new results)");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        try {
            if (wifiScanReceiver != null)
                context.getApplicationContext().unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "scan receiver already unregistered", e);
        }
        worker.shutdownNow();
        super.onDestroyImpl(context, view);
    }

    public WifiManager getWifiManager() {
        return this.wifiManager;
    }

    public static Map<String, List<Sample>> getNodes() {
        return nodes;
    }

    /** Total readings captured across all access points (for the UI status). */
    public static int totalSamples() {
        int total = 0;
        for (List<Sample> v : nodes.values())
            total += v.size();
        return total;
    }

    /** Wrapper so callers don't fight {@code Map} immutability semantics. */
    public static void clearNodes() {
        nodes.clear();
    }
}
