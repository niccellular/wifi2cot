
package com.atakmap.android.wifi2cot;

/**
 * A single Wi-Fi observation: the signal seen from an access point together
 * with the observer's own position at the moment of the reading.
 *
 * <p>RSSI is stored as the <em>raw</em> value in dBm (a negative number such as
 * -55). The old code stored {@code 100 - abs(rssi)}, which threw away the sign
 * and linearised a logarithmic quantity; keeping the raw dBm lets the estimator
 * convert to range (path-loss) and to linear power (weighting) correctly.
 */
public final class Sample {

    /** Raw received signal strength, dBm (negative; closer to 0 is stronger). */
    public final int rssi;
    /** Channel centre frequency, MHz (used to pick the path-loss exponent). */
    public final int freq;
    /** Observer latitude at the time of the reading, decimal degrees. */
    public final double lat;
    /** Observer longitude at the time of the reading, decimal degrees. */
    public final double lng;
    /** Observer altitude, metres HAE, or {@link Double#NaN} if unknown. */
    public final double alt;
    public final String bssid;
    public final String ssid;
    /** Epoch milliseconds when the reading was taken. */
    public final long time;

    public Sample(int rssi, int freq, double lat, double lng, double alt,
                  String bssid, String ssid, long time) {
        this.rssi = rssi;
        this.freq = freq;
        this.lat = lat;
        this.lng = lng;
        this.alt = alt;
        this.bssid = bssid;
        this.ssid = ssid;
        this.time = time;
    }
}
