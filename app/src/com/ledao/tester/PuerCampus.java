package com.ledao.tester;

import android.content.Context;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Campus data for this build. Platform identifiers remain unknown until verified. */
public final class PuerCampus {
    public final List<double[]> checkpoints = new ArrayList<>();
    public final List<double[]> route = new ArrayList<>();
    private final List<double[]> gpsRoute = new ArrayList<>();
    public final List<String> labels = new ArrayList<>();
    public String name;
    public double minimumMeters;
    public int minimumPace;
    public int maximumPace;
    public double lengthMeters;

    public static PuerCampus load(Context context) throws Exception {
        PuerCampus campus = new PuerCampus();
        JSONObject config;
        try (InputStream input = context.getAssets().open("puer-campus.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            config = new JSONObject(bytes.toString("UTF-8"));
        }
        campus.name = config.getString("name");
        campus.minimumMeters = config.getDouble("min_distance_m");
        campus.minimumPace = config.getInt("min_pace_sec_per_km");
        campus.maximumPace = config.getInt("max_pace_sec_per_km");
        JSONArray points = config.getJSONArray("checkpoints");
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            campus.checkpoints.add(new double[]{point.getDouble("latitude"), point.getDouble("longitude")});
            campus.labels.add(point.getString("label"));
        }
        if (campus.checkpoints.size() != 2) throw new IllegalArgumentException("需要两个打卡参考点");
        try (InputStream input = context.getAssets().open(config.getString("reference_track"))) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            double[] previous = null;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() != XmlPullParser.START_TAG || !"trkpt".equals(parser.getName())) continue;
                double lat = Double.parseDouble(parser.getAttributeValue(null, "lat"));
                double lon = Double.parseDouble(parser.getAttributeValue(null, "lon"));
                double[] raw = {lat, lon};
                campus.gpsRoute.add(raw);
                if (previous != null) campus.lengthMeters += distance(previous, raw);
                previous = raw;
                campus.route.add(toMap(lat, lon));
            }
        }
        if (campus.route.size() < 3 || !(campus.lengthMeters > 50)) {
            throw new IllegalArgumentException("参考跑线数据不足");
        }
        return campus;
    }

    /** Rotate the recorded loop for display, without generating a new activity. */
    public List<double[]> preview(int start) {
        return rotate(route, nearestStart(start));
    }

    private int nearestStart(int start) {
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            double meters = distance(route.get(i), checkpoints.get(start));
            if (meters < best) { best = meters; nearest = i; }
        }
        return nearest;
    }

    private static List<double[]> rotate(List<double[]> points, int start) {
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i <= points.size(); i++) result.add(points.get((start + i) % points.size()));
        return result;
    }

    public RouteGen.Path course(int start, double targetMeters) {
        RouteGen.Path path = repeatLap(preview(start), targetMeters);
        if (path.length > targetMeters) {
            double[] end = path.at(targetMeters);
            int last = path.cum.size() - 1;
            while (path.cum.get(last) > targetMeters) last--;
            path.pts.subList(last + 1, path.pts.size()).clear();
            path.cum.subList(last + 1, path.cum.size()).clear();
            if (path.cum.get(last) < targetMeters) {
                path.pts.add(end);
                path.cum.add(targetMeters);
            }
            path.length = targetMeters;
        }
        return path;
    }

    RouteGen.Path gpsLap(int start) {
        return repeatLap(rotate(gpsRoute, nearestStart(start)), 0);
    }

    private static RouteGen.Path repeatLap(List<double[]> lap, double minimumLength) {
        RouteGen.Path path = new RouteGen.Path();
        path.pts.add(lap.get(0));
        path.cum.add(0.0);
        do {
            for (int i = 1; i < lap.size(); i++) {
                double meters = distance(lap.get(i - 1), lap.get(i));
                if (meters == 0) continue;
                path.length += meters;
                path.pts.add(lap.get(i));
                path.cum.add(path.length);
            }
        } while (path.length < minimumLength);
        return path;
    }

    /** Match both local reference points to distinct official coordinates. */
    public int[] matchPoints(List<double[]> official) {
        int[] matched = {-1, -1};
        for (int i = 0; i < checkpoints.size(); i++) {
            for (int j = 0; j < official.size(); j++) {
                if (distance(checkpoints.get(i), official.get(j)) > 25) continue;
                if (matched[i] != -1) return null;
                matched[i] = j;
            }
        }
        return matched[0] >= 0 && matched[1] >= 0 && matched[0] != matched[1] ? matched : null;
    }

    public static double distance(double[] a, double[] b) {
        double lat = Math.toRadians(b[0] - a[0]);
        double lon = Math.toRadians(b[1] - a[1]);
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(a[0]))
                * Math.cos(Math.toRadians(b[0])) * Math.pow(Math.sin(lon / 2), 2);
        return 12742000 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    /** Approximate WGS84 -> GCJ02 conversion for map drawing only. Raw fixes are saved unchanged. */
    public static double[] toMap(double lat, double lon) {
        if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return new double[]{lat, lon};
        double x = lon - 105, y = lat - 35;
        double common = (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        double a = -100 + 2*x + 3*y + 0.2*y*y + 0.1*x*y + 0.2*Math.sqrt(Math.abs(x)) + common;
        a += (20*Math.sin(y*Math.PI) + 40*Math.sin(y*Math.PI/3)) * 2/3;
        a += (160*Math.sin(y*Math.PI/12) + 320*Math.sin(y*Math.PI/30)) * 2/3;
        double b = 300 + x + 2*y + 0.1*x*x + 0.1*x*y + 0.1*Math.sqrt(Math.abs(x)) + common;
        b += (20*Math.sin(x*Math.PI) + 40*Math.sin(x*Math.PI/3)) * 2/3;
        b += (150*Math.sin(x*Math.PI/12) + 300*Math.sin(x*Math.PI/30)) * 2/3;
        double radians = Math.toRadians(lat);
        double magic = 1 - 0.006693421622965943 * Math.pow(Math.sin(radians), 2);
        double root = Math.sqrt(magic);
        a = a * 180 / ((6378245 * (1 - 0.006693421622965943)) / (magic * root) * Math.PI);
        b = b * 180 / (6378245 / root * Math.cos(radians) * Math.PI);
        return new double[]{lat + a, lon + b};
    }
}
