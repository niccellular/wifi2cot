
package com.atakmap.android.wifi2cot;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


public class wifi2cotDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    public static final String TAG = "wifi2cotDropDownReceiver";

    public static final String SHOW_PLUGIN = "com.atakmap.android.wifi2cot.SHOW_PLUGIN";
    private final View templateView;
    private final Context pluginContext;

    private final wifi2cotMapComponent mc;

    private final Button start, stop, guess;

    private Timer timer;

    private boolean scanning = false;

    /**************************** CONSTRUCTOR *****************************/

    public wifi2cotDropDownReceiver(final MapView mapView,
            final Context context, final wifi2cotMapComponent mc) {
        super(mapView);
        this.pluginContext = context;
        this.mc = mc;

        // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
        // In this case, using it is not necessary - but I am putting it here to remind
        // developers to look at this Inflator
        templateView = PluginLayoutInflater.inflate(context,
                R.layout.main_layout, null);

        start = templateView.findViewById(R.id.start);
        stop = templateView.findViewById(R.id.stop);
        guess = templateView.findViewById(R.id.guess);
    }

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
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

            start.setOnClickListener(view -> {
                Log.d(TAG, "Starting scan");
                scanning = true;
                Toast.makeText(MapView._mapView.getContext(), "Starting scan",
                        Toast.LENGTH_LONG).show();
                wifi2cotMapComponent.getNodes().clear();
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        mc.getWifiManager().startScan();
                    }
                },0, 5000);
            });

            stop.setOnClickListener(view -> {
                scanning = false;
                timer.cancel();
                Log.d(TAG, "Stopping scan");
                Toast.makeText(MapView._mapView.getContext(), "Stopping scan",
                        Toast.LENGTH_LONG).show();
            });

            guess.setOnClickListener(view -> {
                Toast.makeText(MapView._mapView.getContext(), "Computing results",
                        Toast.LENGTH_LONG).show();
                compute();
            });
        }
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

    public void compute() {

        Log.d(TAG, "In compute");

        HashMap<String, List<String[]>> nodes = wifi2cotMapComponent.getNodes();

        double[] rssi_sum = new double[nodes.size()];
        double[] SignalRatio_LatLng = new double[nodes.size()];

        // build rssi_sum
        // for every BSSID, sum up all the rssi values rssi value = 100 - abs(rssi)
        int i = 0;
        for (Map.Entry<String, List<String[]>> s: nodes.entrySet()) {
            Log.d(TAG, s.getKey());
            for (String[] l: s.getValue()) {
                rssi_sum[i] += Integer.parseInt(l[0]);
                Log.d(TAG, String.format(Locale.US, "RECORD: %s %s %s", l[0],l[1],l[2]));
            }
            Log.d(TAG, String.format(Locale.US, "RSSI_SUM %f", rssi_sum[i]));
            i++;
        }

        // build signal ratio
        // for every rssi value, divided by the rssi_sum for that point for a SR
        i = 0;
        for (Map.Entry<String, List<String[]>> s: nodes.entrySet()) {
            for (String[] l: s.getValue()) {
                SignalRatio_LatLng[i] = Integer.parseInt(l[0]) / rssi_sum[i];
                Log.d(TAG, String.format(Locale.US, "SR: %f", SignalRatio_LatLng[i]));
            }
            i++;
        }

        // approx triangulation
        // sum up all the lat/lng * SR for that point
        i = 0;
        for (Map.Entry<String, List<String[]>> s: nodes.entrySet()) {
            double lat = 0.0;
            double lng = 0.0;
            String bssid = "";
            String ssid = "";
            int sampleSize = 0;

            for (String[] l: s.getValue()) {
                lat += (Double.parseDouble(l[1]) * SignalRatio_LatLng[i]);
                lng += (Double.parseDouble(l[2]) * SignalRatio_LatLng[i]);
                bssid = l[3];
                ssid = l[4];
                sampleSize++;
            }
            Log.d(TAG, String.format(Locale.US, "LAT: %.14f LNG: %.14f", lat, lng));
            i++;

            CotEvent cotEvent = new CotEvent();

            CoordinatedTime time = new CoordinatedTime();
            cotEvent.setTime(time);
            cotEvent.setStart(time);
            cotEvent.setStale(time.addMinutes(90));

            cotEvent.setUID(bssid);

            cotEvent.setType("a-f-G-I-E");

            cotEvent.setHow("m-g");

            CotPoint cotPoint = new CotPoint(lat, lng, CotPoint.UNKNOWN,
                    CotPoint.UNKNOWN, CotPoint.UNKNOWN);
            cotEvent.setPoint(cotPoint);

            CotDetail cotDetail = new CotDetail("detail");
            cotEvent.setDetail(cotDetail);

            CotDetail cotRemark = new CotDetail("remarks");
            cotRemark.setAttribute("source", "wifi2cot");
            cotRemark.setInnerText(String.format(Locale.US, "SSID: %s\nSample size: %d", ssid, sampleSize));

            cotDetail.addChild(cotRemark);

            if (cotEvent.isValid())
                CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
            else
                Log.e(TAG, "cotEvent was not valid");
        }
    }

    public boolean isScanning() {
        return scanning;
    }
}
