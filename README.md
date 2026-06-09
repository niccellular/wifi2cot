# wifi2cot

ATAK plugin that samples RSSI from nearby Wi-Fi access points as you move, then
estimates where each access point is and plots it as a CoT marker.

## Directions

1. Press **Start scan** and move through the area of interest. **Walk a loop or
   an L-shaped path, not a straight line** — a straight path cannot resolve how
   far the AP is off to one side (the geometry is ambiguous), so spread your
   samples in two dimensions.
2. The status line shows how many APs and samples have been gathered.
3. Press **Stop scan** when done, then **Compute & plot**.

Each AP is dropped as a CoT marker tagged with its SSID/BSSID, the number of
samples used, the estimation method, and an estimated accuracy (which is also
written to the CoT `ce` field).

> Tip: disable **Wi-Fi scan throttling** in Android Developer options, otherwise
> Android limits you to a few scans every couple of minutes.

## How the location is estimated

Two estimators are used automatically (see `Trilateration.java`):

- **Weighted least-squares trilateration.** Each reading is converted to a
  *range* with a log-distance path-loss model and treated as a circle around the
  observer's position. The maximum-likelihood intersection is solved in a local
  east/north (metres) plane, weighting stronger (nearer) readings far more
  heavily. Unlike a centroid, this can place the AP **off** your path.
- **Power-weighted centroid (fallback).** Used when there are fewer than three
  samples, or when the geometry is degenerate (e.g. a straight-line path) and the
  least-squares solution is unstable.

Accuracy depends on calibration: `Trilateration.DEFAULT_REF_RSSI_1M` (RSSI at
1 m) and the path-loss exponents are the main tuning knobs. The maths has no
Android dependencies and is unit-tested in `TrilaterationTest`.

## Building

Targets **ATAK 5.7** (`ATAK_VERSION = 5.7.0`), AGP 8.7 / Gradle 8.14, Java 17,
`compileSdk 36`.
