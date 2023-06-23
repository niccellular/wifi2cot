
package com.atakmap.android.wifi2cot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownMapComponent;

import com.atakmap.coremap.log.Log;
import com.atakmap.android.wifi2cot.plugin.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class wifi2cotMapComponent extends DropDownMapComponent {

    private static final String TAG = "wifi2cotMapComponent";

    private Context pluginContext;

    private MapView mapView;

    private wifi2cotDropDownReceiver ddr;

    private WifiManager wifiManager;

    // nodes will hold k,v for the BSSID and the rssi,lat,lng,bssid,ssid values
    private final static HashMap<String, List<String[]>> nodes = new HashMap<>();

    public void onCreate(final Context context, Intent intent,
            final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        pluginContext = context;
        mapView = view;

        ddr = new wifi2cotDropDownReceiver(
                view, context, this);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(wifi2cotDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean success = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, false);
                if (success) {
                    scanSuccess();
                } else {
                    // scan failure handling
                    scanFailure();
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(wifiScanReceiver, intentFilter);

        boolean success = wifiManager.startScan();
        if (!success) {
            // scan failure handling
            scanFailure();
        }
    }

    double getDistance(int rssi, int txPower, int freq) {
        /*
         * RSSI = TxPower - 10 * n * lg(d)
         * n = 2 (in free space)
         *
         * d = 10 ^ ((TxPower - RSSI) / (10 * n))
         */
        int n = 2;
        if (freq > 5000) {
            n++;
        }
        return Math.pow(10d, ((double) txPower - rssi) / (10 * n));
    }

    private void scanSuccess() {

        if (!ddr.isScanning()) {
            return;
        }

        List<ScanResult> results = wifiManager.getScanResults();
        for (ScanResult s: results) {
            double lat = mapView.getSelfMarker().getPoint().getLatitude();
            double lng = mapView.getSelfMarker().getPoint().getLongitude();

            if (String.valueOf(lat).length() < 12) {
                return;
            } else if (String.valueOf(lng).length() < 12) {
                return;
            }

            // data will be String["rssi", "self.lat", "self.lng", "bssid", "ssid"]
            if (!nodes.containsKey(s.BSSID)) {
                ArrayList<String[]> data = new ArrayList<>();
                String[] sample = new String[5];
                sample[0] = String.valueOf(100 - Math.abs(s.level));
                sample[1] = String.valueOf(lat);
                sample[2] = String.valueOf(lng);
                sample[3] = s.BSSID;
                sample[4] = s.SSID;

                if (sample[1].startsWith("0.0") && sample[2].startsWith("0.0")) {
                    return;
                }

                data.add(sample);
                nodes.put(s.BSSID, data);
            } else {
                List<String[]> data = nodes.get(s.BSSID);
                String[] sample = new String[5];
                sample[0] = String.valueOf(100 - Math.abs(s.level));
                sample[1] = String.valueOf(lat);
                sample[2] = String.valueOf(lng);
                sample[3] = s.BSSID;
                sample[4] = s.SSID;

                if (sample[1].startsWith("0.0") && sample[2].startsWith("0.0")) {
                    return;
                }

                try {
                    data.add(sample);
                    nodes.put(s.BSSID, data);
                } catch (NullPointerException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void scanFailure() {
        // handle failure: new scan did NOT succeed
        // consider using old scan results: these are the OLD results!
        List<ScanResult> results = wifiManager.getScanResults();
        Log.d(TAG, "Scan failed");
//  ... potentially use older scan results ...
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        super.onDestroyImpl(context, view);
    }

    public WifiManager getWifiManager() {
        return this.wifiManager;
    }

    public static HashMap<String, List<String[]>> getNodes() {
        return nodes;
    }

}
