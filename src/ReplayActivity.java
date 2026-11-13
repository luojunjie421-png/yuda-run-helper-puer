package org.campus.puerreplay;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class ReplayActivity extends Activity {
    private static final int TARGET_METERS = 3000;
    private static final int PREVIEW_SECONDS = 30;
    private static final long ROUND_MILLIS = PREVIEW_SECONDS * 1000L;
    private static final int INK = 0xff152f25;
    private static final int SECONDARY = 0xff4e675d;
    private static final int GREEN = 0xff087450;
    private static final int PAPER = 0xfff3f7f5;
    private static final int BORDER = 0xffdce7e0;

    private enum State { READY, PLAYING, STOPPED, COMPLETE }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::advance;
    private CampusTrack track;
    private TrackView map;
    private Button one;
    private Button two;
    private Button action;
    private TextView distance;
    private TextView roundStatus;
    private ProgressBar progress;
    private int times;
    private int shownRound = 1;
    private int shownDistance = -1;
    private long lastReadout;
    private double meters;
    private long elapsed;
    private long resumedAt;
    private State playback = State.READY;
    private boolean foreground;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        times = getPreferences(MODE_PRIVATE).getInt("times", 1) == 2 ? 2 : 1;
        try {
            track = CampusTrack.load(this);
        } catch (Exception failure) {
            track = null;
        }
        if (state != null && track != null) {
            times = state.getInt("times", times) == 2 ? 2 : 1;
            playback = State.valueOf(state.getString("state", State.READY.name()));
            elapsed = Math.max(0, Math.min(state.getLong("elapsed"), times * ROUND_MILLIS));
            calculateFrame();
        }
        buildScreen();
        renderState();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(23), dp(20), dp(22));
        scroll.addView(page);

        TextView title = text(getString(R.string.app_name), 25, INK);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        page.addView(title);
        add(page, text("普洱倚象校区 · 离线轨迹回放", 14, SECONDARY), 4);

        LinearLayout mapCard = card();
        add(page, mapCard, 17);
        TextView mapTitle = text("参考跑道", 16, INK);
        mapTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mapCard.addView(mapTitle);
        if (track != null) {
            map = new TrackView(this, track);
            map.setBackground(surface(0xffedf3ef, 12, 0));
            map.setClipToOutline(true);
            LinearLayout.LayoutParams mapSize = new LinearLayout.LayoutParams(-1, dp(200));
            mapSize.topMargin = dp(9);
            mapCard.addView(map, mapSize);
            add(mapCard, text(String.format(Locale.CHINA, "GPX 实测一圈约 %.0f 米", track.length),
                    12, SECONDARY), 7);
        } else {
            add(mapCard, text("跑道数据无法读取", 14, SECONDARY), 7);
        }

        LinearLayout playbackCard = card();
        add(page, playbackCard, 12);
        roundStatus = text("", 14, GREEN);
        roundStatus.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playbackCard.addView(roundStatus);
        LinearLayout readout = new LinearLayout(this);
        readout.setGravity(Gravity.BOTTOM);
        distance = text("", 35, INK);
        distance.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        distance.setIncludeFontPadding(false);
        readout.addView(distance);
        TextView target = text(" / 3.00 公里", 15, SECONDARY);
        target.setPadding(dp(4), 0, 0, dp(3));
        readout.addView(target);
        add(playbackCard, readout, 6);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(TARGET_METERS);
        progress.setProgressTintList(ColorStateList.valueOf(GREEN));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xffdcece2));
        progress.setContentDescription("本次回放里程进度");
        LinearLayout.LayoutParams progressSize = new LinearLayout.LayoutParams(-1, dp(9));
        progressSize.topMargin = dp(6);
        playbackCard.addView(progress, progressSize);

        TextView timesLabel = text("回放次数", 14, INK);
        timesLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, timesLabel, 15);
        LinearLayout options = new LinearLayout(this);
        one = button("1 次");
        two = button("2 次");
        options.addView(one, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(48), 1);
        second.leftMargin = dp(8);
        options.addView(two, second);
        add(page, options, 6);
        one.setOnClickListener(v -> selectTimes(1));
        two.setOnClickListener(v -> selectTimes(2));

        action = button("开始回放");
        action.setMinHeight(dp(54));
        action.setOnClickListener(v -> {
            if (playback == State.PLAYING) stopPlayback();
            else startPlayback();
        });
        add(page, action, 12);
        add(page, text("加速预览，每次约 30 秒。离开页面时暂停。", 12, SECONDARY), 8);
        setContentView(scroll);
    }

    private void selectTimes(int count) {
        if (playback == State.PLAYING || times == count) return;
        times = count;
        getPreferences(MODE_PRIVATE).edit().putInt("times", count).apply();
        playback = State.READY;
        elapsed = 0;
        calculateFrame();
        renderState();
    }

    private void startPlayback() {
        if (track == null || playback == State.PLAYING) return;
        playback = State.PLAYING;
        elapsed = 0;
        resumedAt = SystemClock.elapsedRealtime();
        calculateFrame();
        renderState();
        advance();
    }

    private void stopPlayback() {
        freeze();
        if (playback == State.PLAYING) playback = State.STOPPED;
        renderState();
    }

    private void advance() {
        if (!foreground || playback != State.PLAYING) return;
        updateElapsed();
        int previous = shownRound;
        calculateFrame();
        if (playback == State.COMPLETE || previous != shownRound) renderState();
        else renderFrame(false);
        if (playback == State.PLAYING) handler.postDelayed(tick, 33);
    }

    private void updateElapsed() {
        long now = SystemClock.elapsedRealtime();
        if (resumedAt != 0) elapsed = Math.min(times * ROUND_MILLIS, elapsed + now - resumedAt);
        resumedAt = now;
    }

    private void calculateFrame() {
        if (elapsed >= times * ROUND_MILLIS) {
            playback = State.COMPLETE;
            shownRound = times;
            meters = TARGET_METERS;
        } else {
            shownRound = (int) (elapsed / ROUND_MILLIS) + 1;
            meters = TARGET_METERS * (elapsed % ROUND_MILLIS) / (double) ROUND_MILLIS;
        }
    }

    private void freeze() {
        handler.removeCallbacks(tick);
        if (playback == State.PLAYING && resumedAt != 0) {
            updateElapsed();
            calculateFrame();
        }
        resumedAt = 0;
    }

    private void renderState() {
        boolean active = playback == State.PLAYING;
        one.setEnabled(!active && track != null);
        two.setEnabled(!active && track != null);
        styleOption(one, times == 1);
        styleOption(two, times == 2);
        action.setEnabled(track != null);
        action.setText(active ? "停止回放" : playback == State.READY ? "开始回放" : "重新回放");
        action.setTextColor(Color.WHITE);
        action.setBackground(surface(active ? 0xffa23c35 : GREEN, 15, 0));
        action.setAlpha(track == null ? 0.45f : 1);

        String caption;
        switch (playback) {
            case PLAYING: caption = "回放中 · 第 " + shownRound + " / " + times + " 次"; break;
            case STOPPED: caption = "已停止 · 第 " + shownRound + " / " + times + " 次"; break;
            case COMPLETE: caption = "回放完成 · " + times + " / " + times + " 次"; break;
            default: caption = track == null ? "无法回放" : "等待回放 · 共 " + times + " 次";
        }
        roundStatus.setText(caption);
        if (active && foreground) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderFrame(true);
    }

    private void renderFrame(boolean forceReadout) {
        if (map != null) map.showDistance(meters);
        long now = SystemClock.elapsedRealtime();
        if (!forceReadout && now - lastReadout < 1000) return;
        lastReadout = now;
        int wholeMeter = (int) meters;
        int hundredths = wholeMeter / 10;
        if (hundredths != shownDistance) {
            shownDistance = hundredths;
            distance.setText(String.format(Locale.CHINA, "%.2f", hundredths / 100.0));
        }
        progress.setProgress(wholeMeter);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (playback == State.PLAYING) {
            resumedAt = SystemClock.elapsedRealtime();
            renderState();
            advance();
        }
    }

    @Override protected void onPause() {
        foreground = false;
        freeze();
        renderState();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("times", times);
        outState.putString("state", playback.name());
        outState.putLong("elapsed", elapsed);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    private void styleOption(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? GREEN : INK);
        button.setBackground(surface(selected ? 0xffdff2e7 : Color.WHITE, 12,
                selected ? GREEN : BORDER));
        button.setAlpha(button.isEnabled() ? 1 : 0.62f);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setStateListAnimator(null);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(surface(Color.WHITE, 16, BORDER));
        return card;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable surface(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
