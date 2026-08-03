package com.laioffer.travelplanner.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolylineCodecTest {

    /** The example from Google's own encoded-polyline specification. */
    @Test
    void decodesTheReferenceExample() {
        List<double[]> points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");

        assertEquals(3, points.size());
        assertEquals(38.5, points.get(0)[0], 1e-6);
        assertEquals(-120.2, points.get(0)[1], 1e-6);
        assertEquals(40.7, points.get(1)[0], 1e-6);
        assertEquals(-120.95, points.get(1)[1], 1e-6);
        assertEquals(43.252, points.get(2)[0], 1e-6);
        assertEquals(-126.453, points.get(2)[1], 1e-6);
    }

    @Test
    void roundTripsThroughEncodeAndDecode() {
        List<double[]> original = List.of(
                new double[]{35.68123, 139.76712},
                new double[]{35.68901, 139.77004},
                new double[]{35.69455, 139.76388},
                new double[]{35.70012, 139.75999});

        List<double[]> decoded = PolylineCodec.decode(PolylineCodec.encode(original));

        assertEquals(original.size(), decoded.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i)[0], decoded.get(i)[0], 1e-5);
            assertEquals(original.get(i)[1], decoded.get(i)[1], 1e-5);
        }
    }

    /** OSRM gives geometry per navigation step; a leg is the concatenation of its steps. */
    @Test
    void joinsSegmentsAndDropsTheDuplicatedJoint() {
        String first = PolylineCodec.encode(List.of(
                new double[]{35.6800, 139.7600},
                new double[]{35.6850, 139.7650}));
        String second = PolylineCodec.encode(List.of(
                new double[]{35.6850, 139.7650}, // same point ends the first segment
                new double[]{35.6900, 139.7700}));

        List<double[]> joined = PolylineCodec.decode(PolylineCodec.join(List.of(first, second)));

        assertEquals(3, joined.size(), "the shared joint should appear once");
        assertEquals(35.6900, joined.getLast()[0], 1e-5);
    }

    @Test
    void returnsNullWhenThereIsNoUsableLine() {
        assertNull(PolylineCodec.join(List.of()));
        assertTrue(PolylineCodec.decode(null).isEmpty());
        assertTrue(PolylineCodec.decode("").isEmpty());
    }
}
