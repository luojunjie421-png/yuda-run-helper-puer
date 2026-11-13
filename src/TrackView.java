package org.campus.puerreplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class TrackView extends View {
    private final CampusTrack track;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path traveled = new Path();
    private final float density;
    private float[] x;
    private float[] y;
    private double distance;

    TrackView(Context context, CampusTrack track) {
        super(context);
        this.track = track;
        density = getResources().getDisplayMetrics().density;
        setContentDescription("普洱倚象校区参考跑道");
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void showDistance(double meters) {
        if (distance != meters) {
            distance = meters;
            invalidate();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
        if (track == null || w == 0 || h == 0) return;
        double left = 0, right = 0, top = 0, bottom = 0;
        for (int i = 0; i < track.east.length; i++) {
            left = Math.min(left, track.east[i]);
            right = Math.max(right, track.east[i]);
            top = Math.min(top, track.south[i]);
            bottom = Math.max(bottom, track.south[i]);
        }
        float padding = 25 * density;
        double scale = Math.min(
                Math.max(1, w - 2 * padding) / Math.max(1, right - left),
                Math.max(1, h - 2 * padding) / Math.max(1, bottom - top));
        double centerX = (left + right) / 2;
        double centerY = (top + bottom) / 2;
        x = new float[track.east.length];
        y = new float[x.length];
        outline.rewind();
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) (w * 0.5 + (track.east[i] - centerX) * scale);
            y[i] = (float) (h * 0.5 + (track.south[i] - centerY) * scale);
            if (i == 0) outline.moveTo(x[i], y[i]);
            else outline.lineTo(x[i], y[i]);
        }
        outline.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffedf3ef);
        canvas.drawPaint(paint);
        paint.setColor(0xffdbe8df);
        for (float a = 14 * density; a < getWidth(); a += 16 * density) {
            for (float b = 14 * density; b < getHeight(); b += 16 * density) {
                canvas.drawCircle(a, b, 0.75f * density, paint);
            }
        }
        if (x == null) return;
        paint.setColor(0xffdcece2);
        canvas.drawPath(outline, paint);
        stroke(canvas, outline, 0xfff9fcfa, 12);
        stroke(canvas, outline, 0xff8ca798, 4);

        double lapMeters = distance % track.length;
        int next = track.segmentEnd(lapMeters);
        int previous = next - 1;
        double fraction = (lapMeters - track.cumulative[previous])
                / (track.cumulative[next] - track.cumulative[previous]);
        float currentX = (float) (x[previous] + (x[next] - x[previous]) * fraction);
        float currentY = (float) (y[previous] + (y[next] - y[previous]) * fraction);
        if (lapMeters > 0) {
            traveled.rewind();
            traveled.moveTo(x[0], y[0]);
            for (int i = 1; i < next; i++) traveled.lineTo(x[i], y[i]);
            traveled.lineTo(currentX, currentY);
            stroke(canvas, traveled, 0xff087450, 5);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff9fcfa);
        canvas.drawCircle(currentX, currentY, 8 * density, paint);
        paint.setColor(0xff087450);
        canvas.drawCircle(currentX, currentY, 5 * density, paint);
    }

    private void stroke(Canvas canvas, Path path, int color, int width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * density);
        paint.setColor(color);
        canvas.drawPath(path, paint);
    }
}
