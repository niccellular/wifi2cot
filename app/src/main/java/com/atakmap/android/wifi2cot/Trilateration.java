
package com.atakmap.android.wifi2cot;

import java.util.List;

/**
 * Estimates the position of a Wi-Fi access point from a set of
 * {@link Sample} observations (RSSI + the observer's position).
 *
 * <p>Two estimators are provided and selected automatically:
 *
 * <ol>
 *   <li><b>Weighted least-squares trilateration.</b> Each observation is turned
 *       into a <em>range</em> via a log-distance path-loss model and treated as
 *       a circle centred on the observer. The maximum-likelihood intersection of
 *       those circles is solved in a local ENU (east/north, metres) tangent
 *       plane. Crucially this can place the AP <em>off</em> the observer's path
 *       &mdash; something a centroid can never do.</li>
 *   <li><b>Power-weighted centroid (fallback).</b> Used when there are fewer
 *       than three samples, or when the geometry is degenerate (e.g. all samples
 *       lie on a line, so the perpendicular offset is unobservable) and the
 *       least-squares normal matrix is singular.</li>
 * </ol>
 *
 * <p>The class is deliberately free of any Android/ATAK dependency so the maths
 * can be unit-tested on a plain JVM (see {@code TrilaterationTest}).
 */
public final class Trilateration {

    /**
     * RSSI (dBm) expected at 1 m from a typical consumer AP. This is the single
     * biggest knob on absolute range accuracy; -40 dBm is a reasonable default
     * for 2.4 GHz consumer gear. Exposed so callers can calibrate.
     */
    public static final double DEFAULT_REF_RSSI_1M = -40.0;

    /** Path-loss exponent for 2.4 GHz (free space 2.0; light indoor 2.2-2.7). */
    public static final double PATH_LOSS_2G = 2.2;
    /** Path-loss exponent for 5 GHz (shorter wavelength attenuates faster). */
    public static final double PATH_LOSS_5G = 2.5;

    /** Metres per degree of latitude (WGS-84 mean; good to ~0.3% anywhere). */
    private static final double M_PER_DEG_LAT = 111_320.0;
    private static final double EPS = 1e-9;
    /** Reject solutions further than this (m) from the samples as nonsense. */
    private static final double MAX_PLAUSIBLE_M = 100_000.0;

    /** Result of an estimate. Immutable. */
    public static final class Estimate {
        public final double lat;
        public final double lng;
        /** Approximate 1-sigma circular error, metres (feeds the CoT {@code ce}). */
        public final double ce;
        public final int sampleCount;
        /** {@code "trilateration"} or {@code "centroid"}. */
        public final String method;

        Estimate(double lat, double lng, double ce, int sampleCount, String method) {
            this.lat = lat;
            this.lng = lng;
            this.ce = ce;
            this.sampleCount = sampleCount;
            this.method = method;
        }
    }

    private Trilateration() {
    }

    /**
     * Log-distance path-loss range, metres, for one reading:
     * {@code d = 10 ^ ((P@1m - RSSI) / (10 * n))}.
     */
    public static double rssiToDistance(int rssiDbm, int freqMhz, double refRssi1m) {
        double n = (freqMhz > 5000) ? PATH_LOSS_5G : PATH_LOSS_2G;
        return Math.pow(10.0, (refRssi1m - rssiDbm) / (10.0 * n));
    }

    public static Estimate estimate(List<Sample> samples) {
        return estimate(samples, DEFAULT_REF_RSSI_1M);
    }

    /**
     * @param samples   observations for a single BSSID
     * @param refRssi1m calibrated RSSI at 1 m (see {@link #DEFAULT_REF_RSSI_1M})
     * @return best estimate, or {@code null} if {@code samples} is empty
     */
    public static Estimate estimate(List<Sample> samples, double refRssi1m) {
        if (samples == null || samples.isEmpty())
            return null;

        final int n = samples.size();

        // Local tangent-plane origin = mean of the observation points. Working in
        // metres (not raw lat/lng degrees) keeps east/north on the same scale.
        double lat0 = 0, lng0 = 0;
        for (Sample s : samples) {
            lat0 += s.lat;
            lng0 += s.lng;
        }
        lat0 /= n;
        lng0 /= n;
        final double mPerDegLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat0));

        double[] x = new double[n];
        double[] y = new double[n];
        double[] r = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            x[i] = (s.lng - lng0) * mPerDegLng;
            y[i] = (s.lat - lat0) * M_PER_DEG_LAT;
            r[i] = rssiToDistance(s.rssi, s.freq, refRssi1m);
            // Reliability weight = linear received power (dBm -> mW). A -50 dBm
            // sample outweighs a -90 dBm one by ~10^4, so strong/near readings
            // dominate, as they should. Only ratios matter, so the unit cancels.
            w[i] = Math.pow(10.0, s.rssi / 10.0);
        }

        Estimate tri = solveLeastSquares(x, y, r, w, n, lat0, lng0, mPerDegLng);
        if (tri != null)
            return tri;
        return centroid(x, y, r, w, n, lat0, lng0, mPerDegLng);
    }

    /**
     * Weighted linear least-squares circle intersection.
     *
     * <p>Each circle {@code (x-xi)^2 + (y-yi)^2 = ri^2} is differenced against a
     * reference circle {@code k} to cancel the quadratic terms, giving a linear
     * system {@code A p = b} with
     * {@code A_i = [2(xi-xk), 2(yi-yk)]} and
     * {@code b_i = (xi^2+yi^2-ri^2) - (xk^2+yk^2-rk^2)}.
     * It is solved via the weighted 2x2 normal equations.
     *
     * @return the estimate, or {@code null} if under-determined / degenerate.
     */
    private static Estimate solveLeastSquares(double[] x, double[] y, double[] r,
            double[] w, int n, double lat0, double lng0, double mPerDegLng) {
        if (n < 3)
            return null;

        // Reference = strongest signal (max weight) -> smallest, most reliable range.
        int k = 0;
        for (int i = 1; i < n; i++)
            if (w[i] > w[k])
                k = i;
        final double dk = x[k] * x[k] + y[k] * y[k] - r[k] * r[k];

        double saa = 0, sab = 0, sbb = 0, sa = 0, sb = 0;
        for (int i = 0; i < n; i++) {
            if (i == k)
                continue;
            double a1 = 2.0 * (x[i] - x[k]);
            double a2 = 2.0 * (y[i] - y[k]);
            double di = x[i] * x[i] + y[i] * y[i] - r[i] * r[i];
            double b = di - dk;
            // Pair reliability: a differenced equation is only as good as its
            // weaker member, so weight by the smaller of the two powers.
            double wi = Math.min(w[i], w[k]);
            saa += wi * a1 * a1;
            sab += wi * a1 * a2;
            sbb += wi * a2 * a2;
            sa += wi * a1 * b;
            sb += wi * a2 * b;
        }

        double det = saa * sbb - sab * sab;
        double scale = saa + sbb;
        // Scale-aware singularity test: collinear samples leave the perpendicular
        // direction unobservable, making the matrix rank-deficient -> fall back.
        if (scale < EPS || Math.abs(det) < EPS * scale * scale)
            return null;

        double ex = (sbb * sa - sab * sb) / det;
        double ey = (saa * sb - sab * sa) / det;

        // Circular error from the weighted RMS range residual.
        double sw = 0, sres = 0;
        for (int i = 0; i < n; i++) {
            double dx = ex - x[i];
            double dy = ey - y[i];
            double residual = Math.sqrt(dx * dx + dy * dy) - r[i];
            sres += w[i] * residual * residual;
            sw += w[i];
        }
        double ce = (sw > 0) ? Math.sqrt(sres / sw) : Double.NaN;

        double lat = lat0 + ey / M_PER_DEG_LAT;
        double lng = lng0 + ex / mPerDegLng;

        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isNaN(ce)
                || Math.hypot(ex, ey) > MAX_PLAUSIBLE_M)
            return null; // numerically blew up -> let the centroid handle it

        return new Estimate(lat, lng, ce, n, "trilateration");
    }

    /** Power-weighted centroid of the observation points (fallback estimator). */
    private static Estimate centroid(double[] x, double[] y, double[] r,
            double[] w, int n, double lat0, double lng0, double mPerDegLng) {
        double sw = 0, sx = 0, sy = 0;
        for (int i = 0; i < n; i++) {
            sw += w[i];
            sx += w[i] * x[i];
            sy += w[i] * y[i];
        }
        if (sw <= 0)
            return null;
        double ex = sx / sw;
        double ey = sy / sw;

        // A centroid is pinned to (inside) the sample path, so its real
        // uncertainty is large: the spread of the samples PLUS how far off-path
        // the AP could be, floored by the closest (strongest) estimated range.
        double svar = 0, minRange = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double dx = ex - x[i];
            double dy = ey - y[i];
            svar += w[i] * (dx * dx + dy * dy);
            minRange = Math.min(minRange, r[i]);
        }
        double spread = Math.sqrt(svar / sw);
        double ce = spread + (minRange == Double.MAX_VALUE ? 0 : minRange);

        double lat = lat0 + ey / M_PER_DEG_LAT;
        double lng = lng0 + ex / mPerDegLng;
        return new Estimate(lat, lng, ce, n, "centroid");
    }
}
