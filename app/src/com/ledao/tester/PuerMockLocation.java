package com.ledao.tester;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/** Replays the original GPX coordinates through Android's test GPS provider. */
public final class PuerMockLocation extends Service {
    private static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
    private static final String STOP = "com.ledao.tester.puer.STOP_MOCK";
    private static final double SPEED_MPS = 1000.0 / 300.0;
    static boolean active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private RouteGen.Path lap;
    private long started;
    private int providersAdded;
    private boolean fixed;
    private int start;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            double meters = (SystemClock.elapsedRealtime() - started) * SPEED_MPS / 1000;
            double[] point = lap.at(fixed ? 0 : meters % lap.length);
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(point[0]);
            location.setLongitude(point[1]);
            location.setAccuracy(5f);
            location.setSpeed(fixed ? 0f : (float) SPEED_MPS);
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            try {
                for (String provider : PROVIDERS) {
                    location.setProvider(provider);
                    manager.setTestProviderLocation(provider, location);
                }
                handler.postDelayed(this, 1000);
            } catch (RuntimeException error) {
                fail("模拟定位已中止，请检查开发者选项授权", error);
            }
        }
    };

    static boolean allowed(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(APP_OPS_SERVICE);
        return appOps != null && appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopReplay();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;
        try {
            fixed = intent.getBooleanExtra("fixed", false);
            start = intent.getIntExtra("start", 0);
            startForeground(7402, notification());
            if (!allowed(this)) {
                fail("请在开发者选项中选择权哥帮你跑-云农大作为模拟定位应用", null);
                return START_NOT_STICKY;
            }
            lap = PuerCampus.load(this).gpsLap(start);
            manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            for (String provider : PROVIDERS) {
                boolean gps = LocationManager.GPS_PROVIDER.equals(provider);
                manager.addTestProvider(provider, !gps, gps, false, false,
                        false, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                providersAdded++;
                manager.setTestProviderEnabled(provider, true);
            }
            started = SystemClock.elapsedRealtime();
            active = true;
            handler.post(tick);
        } catch (Exception error) {
            fail("启动模拟定位失败，请检查开发者选项授权", error);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        String channel = "puer-mock-location";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notifications.createNotificationChannel(new NotificationChannel(
                    channel, "GPX 模拟定位", NotificationManager.IMPORTANCE_LOW));
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 42, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 42,
                new Intent(this, PuerMockLocation.class).setAction(STOP), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("普洱 GPX 模拟定位")
                .setContentText(fixed ? "固定在参考点 " + (start + 1) + " 附近" : "循环回放 · 5:00 分/公里")
                .setContentIntent(open).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "停止模拟定位", stop).build();
    }

    private void fail(String message, Exception error) {
        if (error != null) Log.w("PuerMockLocation", message, error);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopReplay();
        stopSelf();
    }

    private void stopReplay() {
        active = false;
        handler.removeCallbacks(tick);
        while (providersAdded > 0) {
            String provider = PROVIDERS[--providersAdded];
            try { manager.removeTestProvider(provider); }
            catch (RuntimeException error) { Log.w("PuerMockLocation", "移除模拟定位来源失败：" + provider, error); }
        }
        stopForeground(true);
    }

    @Override public void onDestroy() {
        stopReplay();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
