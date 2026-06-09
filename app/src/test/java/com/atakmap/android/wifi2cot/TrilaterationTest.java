
package com.atakmap.android.wifi2cot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for the access-point position estimator. These run on a plain JVM
 * because {@link Trilateration} has no Android dependencies.
 */
public class TrilaterationTest {

    private static final double M_PER_DEG_LAT = 111_320.0;
    private static final double REF = Trilateration.DEFAULT_REF_RSSI_1M;
    private static final int FREQ = 2412; // 2.4 GHz channel 1

    /** Build a synthetic reading whose RSSI is consistent with the path-loss model. */
    private static Sample sampleObservedFrom(double obsLat, double obsLng,
            double apLat, double apLng) {
        double mPerDegLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(obsLat));
        double dx = (apLng - obsLng) * mPerDegLng;
        double dy = (apLat - obsLat) * M_PER_DEG_LAT;
        double dist = Math.max(1.0, Math.hypot(dx, dy));
        double n = Trilateration.PATH_LOSS_2G;
        int rssi = (int) Math.round(REF - 10.0 * n * Math.log10(dist));
        return new Sample(rssi, FREQ, obsLat, obsLng, Double.NaN, "AA:BB", "test", 0L);
    }

    private static double metresBetween(double lat1, double lng1, double lat2, double lng2) {
        double mPerDegLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat1));
        double dx = (lng2 - lng1) * mPerDegLng;
        double dy = (lat2 - lat1) * M_PER_DEG_LAT;
        return Math.hypot(dx, dy);
    }

    @Test
    public void distanceIncreasesAsSignalWeakens() {
        double near = Trilateration.rssiToDistance(-40, FREQ, REF);
        double far = Trilateration.rssiToDistance(-80, FREQ, REF);
        assertTrue("weaker signal must be further", far > near);
        assertEquals("ref RSSI maps to ~1 m", 1.0,
                Trilateration.rssiToDistance((int) REF, FREQ, REF), 0.01);
    }

    @Test
    public void recoversApOffThePath() {
        // Realistic "walk a loop" geometry: observations scattered in 2D around
        // the area (NOT on a single line). The AP sits off to one side; a
        // centroid could never leave the cluster, but trilateration should.
        final double baseLat = 35.0, baseLng = -120.0;
        final double mPerLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(baseLat));

        // AP at east=30 m, north=55 m from base.
        double apLat = baseLat + 55.0 / M_PER_DEG_LAT;
        double apLng = baseLng + 30.0 / mPerLng;

        // Observer positions (east, north) metres — a loop-ish spread.
        double[][] obs = {
                {0, 0}, {50, 0}, {50, 40}, {0, 40},
                {25, -30}, {-30, 20}, {70, 25}, {20, 70}
        };

        List<Sample> samples = new ArrayList<>();
        for (double[] o : obs) {
            double obsLat = baseLat + o[1] / M_PER_DEG_LAT;
            double obsLng = baseLng + o[0] / mPerLng;
            samples.add(sampleObservedFrom(obsLat, obsLng, apLat, apLng));
        }

        Trilateration.Estimate e = Trilateration.estimate(samples);
        assertNotNull(e);
        assertEquals("trilateration", e.method);
        double err = metresBetween(e.lat, e.lng, apLat, apLng);
        assertTrue("estimate within 15 m, was " + err + " m", err < 15.0);
    }

    @Test
    public void straightLinePathFallsBackToCentroid() {
        // A perfectly straight walking path leaves the perpendicular offset
        // unobservable (singular normal matrix). The estimator must degrade
        // gracefully to the centroid rather than emit a wild solution.
        double apLat = 35.0 + 60.0 / M_PER_DEG_LAT;
        double apLng = -120.0;
        double mPerLng = M_PER_DEG_LAT * Math.cos(Math.toRadians(35.0));

        List<Sample> samples = new ArrayList<>();
        for (int i = -4; i <= 4; i++)
            samples.add(sampleObservedFrom(35.0, -120.0 + (i * 25.0) / mPerLng, apLat, apLng));

        Trilateration.Estimate e = Trilateration.estimate(samples);
        assertNotNull(e);
        assertEquals("centroid", e.method);
    }

    @Test
    public void fallsBackToCentroidWhenCollinearWithTwoPoints() {
        // Only two samples -> under-determined -> centroid fallback, no crash.
        List<Sample> samples = new ArrayList<>();
        samples.add(new Sample(-50, FREQ, 35.0, -120.0, Double.NaN, "AA", "t", 0L));
        samples.add(new Sample(-60, FREQ, 35.0001, -120.0, Double.NaN, "AA", "t", 0L));

        Trilateration.Estimate e = Trilateration.estimate(samples);
        assertNotNull(e);
        assertEquals("centroid", e.method);
        assertTrue("centroid CE should be non-trivial", e.ce > 0);
    }

    @Test
    public void emptyInputReturnsNull() {
        assertEquals(null, Trilateration.estimate(new ArrayList<>()));
        assertEquals(null, Trilateration.estimate(null));
    }
}
