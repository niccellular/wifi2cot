
package com.atakmap.android.wifi2cot;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.wifi2cot.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class wifi2cotDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    public static final String TAG = "wifi2cotDropDownReceiver";

    public static final String SHOW_PLUGIN = "com.atakmap.android.wifi2cot.SHOW_PLUGIN";

    /** Wi-Fi APs are re-scanned on this cadence while a scan is running. */
    private static final long SCAN_PERIOD_MS = 5000L;

    private final View templateView;
    private final Context pluginContext;
    private final wifi2cotMapComponent mc;

    private final Button start, stop, guess;
    private final TextView status;

    private Timer timer;
    private volatile boolean scanning = false;

    /**************************** CONSTRUCTOR *****************************/

    public wifi2cotDropDownReceiver(final MapView mapView,
            final Context context, final wifi2cotMapComponent mc) {
        super(mapView);
        this.pluginContext = context;
        this.mc = mc;

        templateView = PluginLayoutInflater.inflate(context, R.layout.main_layout, null);

        start = templateView.findViewById(R.id.start);
        stop = templateView.findViewById(R.id.stop);
        guess = templateView.findViewById(R.id.guess);
        status = templateView.findViewById(R.id.status);

        setControlsForIdle();
    }

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
        stopScanning();
    }

    /**************************** INHERITED METHODS *****************************/

    @Override
    public void onReceive(Context context, Intent intent) {

        final String action = intent.getAction();
        if (action == null)
            return;

        if (action.equals(SHOW_PLUGIN)) {
            Log.d(TAG, "showing plugin drop down");
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);

            start.setOnClickListener(view -> startScanning());
            stop.setOnClickListener(view -> stopScanning());
            guess.setOnClickListener(view -> compute());

            updateStatus();
        }
    }

    /**************************** SCANNING *****************************/

    private void startScanning() {
        if (scanning)
            return;
        Log.d(TAG, "Starting scan");
        wifi2cotMapComponent.clearNodes();
        scanning = true;
        setControlsForScanning();
        toast("Scanning started — walk the area to gather signal");

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (mc.getWifiManager() != null && !mc.getWifiManager().startScan())
                    Log.d(TAG, "startScan() returned false (likely throttled)");
                status.post(() -> updateStatus());
            }
        }, 0, SCAN_PERIOD_MS);
    }

    private void stopScanning() {
        if (!scanning && timer == null)
            return;
        Log.d(TAG, "Stopping scan");
        scanning = false;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        setControlsForIdle();
        updateStatus();
        toast("Scanning stopped");
    }

    /**************************** ESTIMATION *****************************/

    /** Estimate each AP's position from its samples and push a CoT marker. */
    public void compute() {
        Log.d(TAG, "In compute");

        final Map<String, List<Sample>> nodes = wifi2cotMapComponent.getNodes();
        if (nodes.isEmpty()) {
            toast("No access points captured yet — start a scan first");
            return;
        }

        int emitted = 0;
        for (Map.Entry<String, List<Sample>> entry : nodes.entrySet()) {
            final List<Sample> samples = entry.getValue();
            if (samples == null || samples.isEmpty())
                continue;

            Trilateration.Estimate est = Trilateration.estimate(samples);
            if (est == null)
                continue;

            Sample meta = samples.get(0);
            String bssid = meta.bssid;
            String ssid = (meta.ssid == null || meta.ssid.isEmpty()) ? bssid : meta.ssid;

            if (dispatch(est, bssid, ssid))
                emitted++;
        }

        Log.d(TAG, "Plotted " + emitted + " access points");
        toast("Plotted " + emitted + " access point(s)");
        updateStatus();
    }

    private boolean dispatch(Trilateration.Estimate est, String bssid, String ssid) {
        CotEvent cotEvent = new CotEvent();

        CoordinatedTime time = new CoordinatedTime();
        cotEvent.setTime(time);
        cotEvent.setStart(time);
        cotEvent.setStale(time.addMinutes(90));

        // Stable, namespaced UID so re-running compute() updates the same marker
        // instead of spawning duplicates.
        cotEvent.setUID("wifi2cot." + bssid);
        cotEvent.setType("a-u-G"); // atom, unknown affiliation, ground
        cotEvent.setHow("m-g");

        double ce = Double.isNaN(est.ce) ? CotPoint.UNKNOWN : Math.max(1.0, est.ce);
        CotPoint cotPoint = new CotPoint(est.lat, est.lng, CotPoint.UNKNOWN, ce,
                CotPoint.UNKNOWN);
        cotEvent.setPoint(cotPoint);

        CotDetail detail = new CotDetail("detail");
        cotEvent.setDetail(detail);

        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", ssid);
        detail.addChild(contact);

        // Tell ATAK this position is calculated, not a real GPS fix.
        CotDetail precision = new CotDetail("precisionlocation");
        precision.setAttribute("geopointsrc", "CALC");
        precision.setAttribute("altsrc", "???");
        detail.addChild(precision);

        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "wifi2cot");
        remarks.setInnerText(String.format(Locale.US,
                "SSID: %s\nBSSID: %s\nSamples: %d\nMethod: %s\nEst. accuracy: %s",
                ssid, bssid, est.sampleCount, est.method,
                Double.isNaN(est.ce) ? "unknown"
                        : String.format(Locale.US, "%.0f m", est.ce)));
        detail.addChild(remarks);

        if (cotEvent.isValid()) {
            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
            return true;
        }
        Log.e(TAG, "cotEvent was not valid for " + bssid);
        return false;
    }

    /**************************** UI HELPERS *****************************/

    private void updateStatus() {
        if (status == null)
            return;
        int aps = wifi2cotMapComponent.getNodes().size();
        int samples = wifi2cotMapComponent.totalSamples();
        String text = scanning
                ? String.format(Locale.US, "Scanning…  %d APs · %d samples", aps, samples)
                : (aps == 0 ? "Idle — press Start to scan"
                        : String.format(Locale.US, "Ready — %d APs · %d samples", aps, samples));
        status.setText(text);
    }

    private void setControlsForScanning() {
        start.setEnabled(false);
        stop.setEnabled(true);
        guess.setEnabled(true);
        updateStatus();
    }

    private void setControlsForIdle() {
        start.setEnabled(true);
        stop.setEnabled(false);
        guess.setEnabled(true);
        updateStatus();
    }

    private void toast(String msg) {
        Toast.makeText(getMapView().getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    public boolean isScanning() {
        return scanning;
    }
}
