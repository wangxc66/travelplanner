package com.laioffer.travelplanner.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Google's encoded polyline algorithm, precision 5 — the format both the Routes API and OSRM speak,
 * and the one the browser decodes to draw a route.
 *
 * <p>We need the encoder because OSRM hands back geometry per navigation <em>step</em>, while a leg of
 * an itinerary spans many steps: decode the steps, splice them, re-encode once.
 */
public final class PolylineCodec {

    private PolylineCodec() {
    }

    public static List<double[]> decode(String encoded) {
        List<double[]> points = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return points;
        }
        int index = 0;
        int lat = 0;
        int lng = 0;
        while (index < encoded.length()) {
            int shift = 0;
            int result = 0;
            int b;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += (result & 1) != 0 ? ~(result >> 1) : result >> 1;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += (result & 1) != 0 ? ~(result >> 1) : result >> 1;

            points.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return points;
    }

    public static String encode(List<double[]> points) {
        StringBuilder out = new StringBuilder();
        long lat = 0;
        long lng = 0;
        for (double[] point : points) {
            long iLat = Math.round(point[0] * 1e5);
            long iLng = Math.round(point[1] * 1e5);
            encodeSigned(iLat - lat, out);
            encodeSigned(iLng - lng, out);
            lat = iLat;
            lng = iLng;
        }
        return out.toString();
    }

    private static void encodeSigned(long value, StringBuilder out) {
        long v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }

    /** Splices consecutive encoded segments into one, dropping the duplicated joint points. */
    public static String join(List<String> segments) {
        List<double[]> merged = new ArrayList<>();
        for (String segment : segments) {
            List<double[]> points = decode(segment);
            for (double[] point : points) {
                if (merged.isEmpty()) {
                    merged.add(point);
                    continue;
                }
                double[] last = merged.getLast();
                if (Math.abs(last[0] - point[0]) > 1e-6 || Math.abs(last[1] - point[1]) > 1e-6) {
                    merged.add(point);
                }
            }
        }
        return merged.size() < 2 ? null : encode(merged);
    }
}
