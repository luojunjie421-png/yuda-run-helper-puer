package org.campus.puerreplay;

import android.content.Context;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Geometry of the bundled GPX loop, in local meters. */
final class CampusTrack {
    private static final double EARTH_RADIUS = 6_371_000;
    final double[] east;
    final double[] south;
    final double[] cumulative;
    final double length;

    static CampusTrack load(Context context) throws Exception {
        List<double[]> points = new ArrayList<>();
        try (InputStream stream = context.getResources().openRawResource(R.raw.campus_route)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"trkpt".equals(parser.getName())) continue;
                double lat = Double.parseDouble(parser.getAttributeValue(null, "lat"));
                double lon = Double.parseDouble(parser.getAttributeValue(null, "lon"));
                if (!Double.isFinite(lat) || !Double.isFinite(lon)
                        || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    throw new IOException("Invalid GPX coordinate");
                }
                double[] point = {lat, lon};
                if (points.isEmpty() || distance(points.get(points.size() - 1), point) >= 0.01) {
                    points.add(point);
                }
            }
        }
        if (points.size() < 3) throw new IOException("GPX loop has too few points");
        double[] start = points.get(0);
        double gap = distance(points.get(points.size() - 1), start);
        if (gap > 15) throw new IOException("GPX route does not form a loop");
        if (gap >= 0.01) points.add(start);
        return new CampusTrack(points);
    }

    private CampusTrack(List<double[]> points) {
        int size = points.size();
        east = new double[size];
        south = new double[size];
        cumulative = new double[size];
        double[] start = points.get(0);
        double cosLatitude = Math.cos(Math.toRadians(start[0]));
        for (int i = 1; i < size; i++) {
            double[] point = points.get(i);
            east[i] = EARTH_RADIUS * cosLatitude * Math.toRadians(point[1] - start[1]);
            south[i] = EARTH_RADIUS * Math.toRadians(start[0] - point[0]);
            cumulative[i] = cumulative[i - 1] + distance(points.get(i - 1), point);
        }
        length = cumulative[size - 1];
    }

    int segmentEnd(double meters) {
        int index = Arrays.binarySearch(cumulative, meters);
        return index >= 0 ? Math.max(1, index) : -index - 1;
    }

    private static double distance(double[] a, double[] b) {
        double latA = Math.toRadians(a[0]);
        double latB = Math.toRadians(b[0]);
        double lat = Math.sin((latB - latA) / 2);
        double lon = Math.sin(Math.toRadians(b[1] - a[1]) / 2);
        double h = lat * lat + Math.cos(latA) * Math.cos(latB) * lon * lon;
        return EARTH_RADIUS * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }
}
