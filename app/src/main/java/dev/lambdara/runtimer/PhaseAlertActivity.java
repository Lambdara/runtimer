package dev.lambdara.runtimer;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class PhaseAlertActivity extends Activity {
    static Intent intentFor(Context context) {
        return new Intent(context, PhaseAlertActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepareLockScreenWindow();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JSONObject active = RunLogStore.loadActive(this);
        if (active == null || active.optInt("state") != RunService.STATE_AWAITING_DISMISS) {
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render();
    }

    @SuppressLint("ObsoleteSdkInt")
    @SuppressWarnings("deprecation")
    private void prepareLockScreenWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
    }

    private void render() {
        JSONObject active = RunLogStore.loadActive(this);
        AlertCopy copy = alertCopy(active);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(36), dp(28), dp(28));
        root.setBackgroundColor(Color.WHITE);
        setContentView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("Phase alert");
        eyebrow.setTextColor(Color.rgb(0, 106, 106));
        eyebrow.setTextSize(14);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText(copy.finished);
        title.setTextColor(Color.rgb(23, 31, 36));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(18), 0, dp(10));
        root.addView(title, titleParams);

        TextView next = new TextView(this);
        next.setText(copy.next);
        next.setTextColor(Color.rgb(73, 82, 88));
        next.setTextSize(20);
        next.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nextParams.setMargins(0, 0, 0, dp(34));
        root.addView(next, nextParams);

        Button primary = new Button(this);
        primary.setText(copy.primaryAction);
        primary.setAllCaps(false);
        primary.setTextSize(18);
        primary.setOnClickListener(view -> {
            startService(new Intent(this, RunService.class).setAction(RunService.ACTION_DISMISS_ALERT));
            finish();
        });
        root.addView(primary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)));

        if (!copy.isFinalPhase) {
            Button stop = new Button(this);
            stop.setText("End run early");
            stop.setAllCaps(false);
            stop.setOnClickListener(view -> {
                startService(new Intent(this, RunService.class).setAction(RunService.ACTION_STOP));
                finish();
            });
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(52));
            stopParams.setMargins(0, dp(14), 0, 0);
            root.addView(stop, stopParams);
        }
    }

    private AlertCopy alertCopy(JSONObject active) {
        if (active == null) {
            return new AlertCopy("Finished current phase", "Open the app to continue.", "Dismiss", true);
        }
        int phaseIndex = active.optInt("phaseIndex", 0);
        try {
            JSONArray phases = active.getJSONArray("phases");
            JSONObject finished = phases.getJSONObject(phaseIndex);
            boolean finalPhase = phaseIndex >= phases.length() - 1;
            String finishedText = "Finished: " + phaseTitle(finished);
            String nextText = finalPhase
                    ? "Run complete"
                    : "Next: " + phaseTitle(phases.getJSONObject(phaseIndex + 1));
            String primary = finalPhase ? "Finish run" : "Start next phase";
            return new AlertCopy(finishedText, nextText, primary, finalPhase);
        } catch (JSONException exception) {
            return new AlertCopy("Finished current phase", "Open the app to continue.", "Dismiss", true);
        }
    }

    private String phaseTitle(JSONObject phase) {
        long duration = phase.optLong("durationMillis");
        String label = phase.optString("label");
        if (label == null || label.trim().isEmpty()) {
            return RunPhase.formatHumanDuration(duration);
        }
        return RunPhase.formatHumanDuration(duration) + " " + label.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AlertCopy {
        final String finished;
        final String next;
        final String primaryAction;
        final boolean isFinalPhase;

        AlertCopy(String finished, String next, String primaryAction, boolean isFinalPhase) {
            this.finished = finished;
            this.next = next;
            this.primaryAction = primaryAction;
            this.isFinalPhase = isFinalPhase;
        }
    }
}
