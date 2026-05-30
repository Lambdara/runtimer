package dev.lambdara.runtimer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 71;
    private static final int COLOR_TEXT = 0xFF161C20;
    private static final int COLOR_MUTED = 0xFF495258;
    private static final int COLOR_BORDER = 0xFFE2E6E9;
    private static final int COLOR_ACCENT = 0xFF006A6A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<PhaseRow> phaseRows = new ArrayList<>();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000L);
        }
    };
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    private LockableScrollView scrollView;
    private LinearLayout root;
    private LinearLayout scheduleRows;
    private TextView permissionText;
    private Button fullScreenSettingsButton;
    private TextView activeStatus;
    private Button startButton;
    private Button stopButton;
    private LinearLayout historyList;
    private Button deleteAllHistoryButton;
    private boolean pendingStartAfterPermissions;
    private boolean scheduleRowsEnabled = true;
    private PhaseRow draggedRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RunLogStore.migrateRunDistances(this);
        buildUi();
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(RunService.ACTION_STATUS_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            refresh();
            if (pendingStartAfterPermissions && hasRequiredPermissions()) {
                pendingStartAfterPermissions = false;
                startRun();
            }
        }
    }

    private void buildUi() {
        scrollView = new LockableScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 248, 250));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        TextView title = new TextView(this);
        title.setText("Run Timer");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        permissionText = smallText("");
        root.addView(permissionText, blockParams(dp(14)));

        fullScreenSettingsButton = new Button(this);
        fullScreenSettingsButton.setText("Enable lock-screen alerts");
        fullScreenSettingsButton.setAllCaps(false);
        fullScreenSettingsButton.setOnClickListener(view -> openFullScreenSettings());
        root.addView(fullScreenSettingsButton, buttonParams(0, dp(8), 0, dp(14)));

        root.addView(sectionTitle("Schedule"));
        buildScheduleEditor();

        startButton = new Button(this);
        startButton.setText("Start run");
        startButton.setAllCaps(false);
        startButton.setTextSize(18);
        startButton.setOnClickListener(view -> startRun());
        root.addView(startButton, buttonParams(0, dp(14), 0, 0));

        activeStatus = smallText("");
        activeStatus.setBackground(cardBackground(Color.WHITE));
        activeStatus.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(activeStatus, blockParams(dp(18)));

        stopButton = new Button(this);
        stopButton.setText("End current run");
        stopButton.setAllCaps(false);
        stopButton.setOnClickListener(view -> confirmStopRun());
        root.addView(stopButton, buttonParams(0, dp(10), 0, dp(20)));

        buildHistoryHeader();
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyList);

        addPhaseRow(new PhaseDraft(10, 0, "easy"));
        addPhaseRow(new PhaseDraft(20, 0, "hard"));
        addPhaseRow(new PhaseDraft(10, 0, "easy"));
        ensureTrailingEmptyRow();
        refresh();
    }

    private void buildScheduleEditor() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(headerCell("", 56));
        header.addView(headerCell("Min", 62));
        header.addView(headerCell("Sec", 62));
        header.addView(headerCell("Label", 0));
        header.addView(headerCell("", 48));
        root.addView(header);

        scheduleRows = new LinearLayout(this);
        scheduleRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(scheduleRows);
    }

    private void buildHistoryHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(18), 0, dp(8));
        root.addView(header, params);

        TextView title = new TextView(this);
        title.setText("History");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        deleteAllHistoryButton = new Button(this);
        deleteAllHistoryButton.setText("Delete all");
        deleteAllHistoryButton.setAllCaps(false);
        deleteAllHistoryButton.setOnClickListener(view -> confirmDeleteAllRuns());
        header.addView(deleteAllHistoryButton, new LinearLayout.LayoutParams(dp(116), dp(44)));
    }

    private void startRun() {
        if (!hasRequiredPermissions()) {
            pendingStartAfterPermissions = true;
            requestPermissions(requiredPermissions(), REQ_PERMISSIONS);
            return;
        }
        if (!isLocationEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on location")
                    .setMessage("The timer can start, but distance will not be useful until phone location is enabled.")
                    .setPositiveButton("Open settings", (dialog, which) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Start anyway", (dialog, which) -> actuallyStartRun())
                    .show();
            return;
        }
        actuallyStartRun();
    }

    private void actuallyStartRun() {
        try {
            ArrayList<RunPhase> phases = collectSchedulePhases();
            String scheduleText = scheduleText(phases);
            Intent intent = new Intent(this, RunService.class)
                    .setAction(RunService.ACTION_START)
                    .putExtra(RunService.EXTRA_PHASES_JSON, RunPhase.toJsonString(phases))
                    .putExtra(RunService.EXTRA_SCHEDULE_TEXT, scheduleText);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "Run started", Toast.LENGTH_SHORT).show();
            refresh();
        } catch (IllegalArgumentException | JSONException exception) {
            new AlertDialog.Builder(this)
                    .setTitle("Check schedule")
                    .setMessage(exception.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void confirmStopRun() {
        new AlertDialog.Builder(this)
                .setTitle("End run early?")
                .setMessage("The run will be saved as aborted with the distance recorded so far.")
                .setPositiveButton("End run", (dialog, which) -> {
                    startService(new Intent(this, RunService.class).setAction(RunService.ACTION_STOP));
                    refresh();
                })
                .setNegativeButton("Keep running", null)
                .show();
    }

    private void refresh() {
        updatePermissionViews();
        JSONObject active = RunLogStore.loadActive(this);
        boolean running = active != null && active.optBoolean("active");
        setScheduleRowsEnabled(!running);
        startButton.setEnabled(!running);
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        activeStatus.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            activeStatus.setText(activeRunText(active));
        }
        renderHistory();
    }

    private void updatePermissionViews() {
        StringBuilder builder = new StringBuilder();
        if (!hasRequiredPermissions()) {
            builder.append("Location and notification permissions are needed before starting.");
        }
        if (!canUseFullScreenIntent()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("Lock-screen alerts need full-screen notification access.");
        }
        permissionText.setText(builder.toString());
        permissionText.setVisibility(builder.length() == 0 ? View.GONE : View.VISIBLE);
        fullScreenSettingsButton.setVisibility(canUseFullScreenIntent() ? View.GONE : View.VISIBLE);
    }

    private String activeRunText(JSONObject active) {
        int state = active.optInt("state");
        int phaseIndex = active.optInt("phaseIndex");
        JSONArray phases = active.optJSONArray("phases");
        String phaseTitle = "Current phase";
        if (phases != null && phaseIndex >= 0 && phaseIndex < phases.length()) {
            phaseTitle = phaseTitle(phases.optJSONObject(phaseIndex));
        }
        double distance = sumDistances(active.optJSONArray("phaseMeters"));
        if (state == RunService.STATE_AWAITING_DISMISS) {
            return "Alert waiting\n" + phaseTitle + " finished\nDistance: " + RunService.formatDistance(distance);
        }
        long remaining = Math.max(0L, active.optLong("phaseEndsElapsedMillis") - SystemClock.elapsedRealtime());
        return "Running\n" + phaseTitle + "\nRemaining: " + RunPhase.formatClock(remaining) + "\nDistance: " + RunService.formatDistance(distance);
    }

    private void addPhaseRow(PhaseDraft draft) {
        PhaseRow row = new PhaseRow(draft);
        phaseRows.add(row);
        scheduleRows.addView(row.view, rowLayoutParams());
        updateScheduleRowControls();
    }

    private void ensureTrailingEmptyRow() {
        if (phaseRows.isEmpty() || phaseRows.get(phaseRows.size() - 1).hasContent()) {
            addPhaseRow(new PhaseDraft(0, 0, ""));
        }
        updateScheduleRowControls();
    }

    private void deletePhaseRow(PhaseRow row) {
        int index = phaseRows.indexOf(row);
        if (index < 0) {
            return;
        }
        phaseRows.remove(index);
        scheduleRows.removeView(row.view);
        ensureTrailingEmptyRow();
    }

    private void setScheduleRowsEnabled(boolean enabled) {
        scheduleRowsEnabled = enabled;
        updateScheduleRowControls();
    }

    private void updateScheduleRowControls() {
        for (int i = 0; i < phaseRows.size(); i++) {
            PhaseRow row = phaseRows.get(i);
            boolean content = row.hasContent();
            boolean trailingEmpty = !content && i == phaseRows.size() - 1;
            boolean editable = scheduleRowsEnabled;
            row.minutesInput.setEnabled(editable);
            row.secondsInput.setEnabled(editable);
            row.labelInput.setEnabled(editable);
            row.deleteButton.setEnabled(editable && !trailingEmpty);
            row.deleteButton.setVisibility(trailingEmpty ? View.INVISIBLE : View.VISIBLE);
            row.dragHandle.setEnabled(editable && content && movableRowCount() > 1);
            row.dragHandle.setAlpha(row.dragHandle.isEnabled() ? 1f : 0.35f);
        }
    }

    private int movableRowCount() {
        int count = 0;
        for (PhaseRow row : phaseRows) {
            if (row.hasContent()) {
                count++;
            }
        }
        return count;
    }

    private ArrayList<RunPhase> collectSchedulePhases() {
        ArrayList<RunPhase> phases = new ArrayList<>();
        int visibleRow = 1;
        for (PhaseRow row : phaseRows) {
            if (!row.hasContent()) {
                continue;
            }
            int minutes = parseNumber(row.minutesInput, "minutes", visibleRow);
            int seconds = parseNumber(row.secondsInput, "seconds", visibleRow);
            if (seconds > 59) {
                throw new IllegalArgumentException("Row " + visibleRow + ": seconds must be 0 to 59.");
            }
            long totalSeconds = minutes * 60L + seconds;
            if (totalSeconds < 1L) {
                throw new IllegalArgumentException("Row " + visibleRow + ": enter at least 1 second.");
            }
            String label = row.labelInput.getText().toString().trim();
            if (label.isEmpty()) {
                label = "phase " + (phases.size() + 1);
            }
            phases.add(new RunPhase(totalSeconds * 1000L, label));
            visibleRow++;
        }
        if (phases.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one phase.");
        }
        return phases;
    }

    private int parseNumber(EditText input, String fieldName, int row) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Row " + row + ": check " + fieldName + ".");
        }
    }

    private String scheduleText(ArrayList<RunPhase> phases) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < phases.size(); i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(phases.get(i).title());
        }
        return builder.toString();
    }

    private boolean handleDragTouch(PhaseRow row, MotionEvent event) {
        if (!scheduleRowsEnabled || !row.hasContent()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginScheduleDrag(row);
                return true;
            case MotionEvent.ACTION_MOVE:
                lockScheduleScroll(true);
                moveDraggedRow(event.getRawY());
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                finishScheduleDrag();
                return true;
            default:
                return true;
        }
    }

    private void beginScheduleDrag(PhaseRow row) {
        draggedRow = row;
        row.view.setAlpha(0.72f);
        lockScheduleScroll(true);
    }

    private void finishScheduleDrag() {
        if (draggedRow != null) {
            draggedRow.view.setAlpha(1f);
        }
        draggedRow = null;
        lockScheduleScroll(false);
    }

    private void lockScheduleScroll(boolean locked) {
        if (scrollView != null) {
            scrollView.setScrollEnabled(!locked);
            scrollView.requestDisallowInterceptTouchEvent(locked);
        }
        if (scheduleRows != null) {
            scheduleRows.requestDisallowInterceptTouchEvent(locked);
        }
    }

    private void moveDraggedRow(float rawY) {
        if (draggedRow == null) {
            return;
        }
        int index = phaseRows.indexOf(draggedRow);
        if (index < 0) {
            return;
        }
        int lastMovable = lastMovableIndex();
        if (index > 0 && rawY < rowCenterY(phaseRows.get(index - 1))) {
            swapScheduleRows(index, index - 1);
        } else if (index < lastMovable && rawY > rowCenterY(phaseRows.get(index + 1))) {
            swapScheduleRows(index, index + 1);
        }
    }

    private int lastMovableIndex() {
        int last = phaseRows.size() - 1;
        while (last >= 0 && !phaseRows.get(last).hasContent()) {
            last--;
        }
        return last;
    }

    private float rowCenterY(PhaseRow row) {
        int[] location = new int[2];
        row.view.getLocationOnScreen(location);
        return location[1] + row.view.getHeight() / 2f;
    }

    private void swapScheduleRows(int first, int second) {
        Collections.swap(phaseRows, first, second);
        scheduleRows.removeAllViews();
        for (PhaseRow row : phaseRows) {
            scheduleRows.addView(row.view, rowLayoutParams());
        }
    }

    private void renderHistory() {
        historyList.removeAllViews();
        JSONArray runs = RunLogStore.loadRuns(this);
        deleteAllHistoryButton.setVisibility(runs.length() == 0 ? View.GONE : View.VISIBLE);
        if (runs.length() == 0) {
            TextView empty = smallText("No completed runs yet.");
            empty.setPadding(0, dp(8), 0, 0);
            historyList.addView(empty);
            return;
        }
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                historyList.addView(historyCard(run), blockParams(dp(10)));
            }
        }
    }

    private View historyCard(JSONObject run) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(cardBackground(Color.WHITE));
        card.setClickable(true);
        card.setOnClickListener(view -> openRunMap(run));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean aborted = run.optBoolean("aborted");
        String date = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
                .format(new Date(run.optLong("startedAt")));
        TextView title = new TextView(this);
        title.setText((aborted ? "Aborted - " : "Completed - ") + date);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button delete = new Button(this);
        delete.setText("Delete");
        delete.setAllCaps(false);
        delete.setOnClickListener(view -> confirmDeleteRun(run));
        top.addView(delete, new LinearLayout.LayoutParams(dp(92), dp(44)));

        TextView total = smallText("Total: " + RunService.formatDistance(run.optDouble("totalDistanceMeters")));
        total.setPadding(0, dp(4), 0, dp(8));
        card.addView(total);

        JSONArray phases = run.optJSONArray("phases");
        if (phases != null) {
            for (int i = 0; i < phases.length(); i++) {
                JSONObject phase = phases.optJSONObject(i);
                if (phase != null) {
                    TextView line = smallText(phaseLine(phase));
                    line.setPadding(0, dp(3), 0, 0);
                    card.addView(line);
                }
            }
        }

        if (aborted && run.has("abortedLatitude")) {
            String location = String.format(Locale.US,
                    "Aborted at %.5f, %.5f",
                    run.optDouble("abortedLatitude"),
                    run.optDouble("abortedLongitude"));
            TextView abortedAt = smallText(location);
            abortedAt.setPadding(0, dp(8), 0, 0);
            card.addView(abortedAt);
        }

        return card;
    }

    private void openRunMap(JSONObject run) {
        long id = run.optLong("id", run.optLong("startedAt"));
        startActivity(RunMapActivity.intentFor(this, id));
    }

    private void confirmDeleteRun(JSONObject run) {
        long id = run.optLong("id", run.optLong("startedAt"));
        new AlertDialog.Builder(this)
                .setTitle("Delete run?")
                .setMessage("This run will be removed from history.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    RunLogStore.deleteRun(this, id);
                    renderHistory();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAllRuns() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all runs?")
                .setMessage("All completed and aborted run history will be removed.")
                .setPositiveButton("Delete all", (dialog, which) -> {
                    RunLogStore.clearRuns(this);
                    renderHistory();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String phaseLine(JSONObject phase) {
        String title = phase.optString("title", "Phase");
        long actual = phase.optLong("actualDurationMillis");
        long planned = phase.optLong("plannedDurationMillis");
        double meters = phase.optDouble("distanceMeters");
        boolean completed = phase.optBoolean("completed");
        if (completed) {
            return title + " - " + RunService.formatDistance(meters) + " in " + RunPhase.formatClock(actual);
        }
        if (actual > 0L) {
            return title + " - " + RunService.formatDistance(meters) + " in " + RunPhase.formatClock(actual) + " of " + RunPhase.formatClock(planned);
        }
        return title + " - not started";
    }

    private String phaseTitle(JSONObject phase) {
        if (phase == null) {
            return "Current phase";
        }
        long duration = phase.optLong("durationMillis");
        String label = phase.optString("label");
        if (label == null || label.trim().isEmpty()) {
            return RunPhase.formatHumanDuration(duration);
        }
        return RunPhase.formatHumanDuration(duration) + " " + label.trim();
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean canUseFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34) {
            return true;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager.canUseFullScreenIntent();
    }

    private void openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private boolean isLocationEnabled() {
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) {
            return false;
        }
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static double sumDistances(JSONArray array) {
        if (array == null) {
            return 0d;
        }
        double total = 0d;
        for (int i = 0; i < array.length(); i++) {
            total += array.optDouble(i, 0d);
        }
        return total;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(18), 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView smallText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(15);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private TextView headerCell(String text, int widthDp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = widthDp == 0
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(3), 0, dp(3), dp(5));
        view.setLayoutParams(params);
        return view;
    }

    private EditText rowInput(String text, int inputType, int maxLength) {
        EditText input = new EditText(this);
        input.setText(text);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setInputType(inputType);
        input.setPadding(dp(8), 0, dp(8), 0);
        input.setBackground(cardBackground(Color.WHITE));
        if (maxLength > 0) {
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        }
        return input;
    }

    private GradientDrawable cardBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), COLOR_BORDER);
        return drawable;
    }

    private LinearLayout.LayoutParams buttonParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private LinearLayout.LayoutParams blockParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams rowLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams rowCellParams(int widthDp) {
        LinearLayout.LayoutParams params = widthDp == 0
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LockableScrollView extends ScrollView {
        private boolean scrollEnabled = true;

        LockableScrollView(Context context) {
            super(context);
        }

        void setScrollEnabled(boolean scrollEnabled) {
            this.scrollEnabled = scrollEnabled;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            return scrollEnabled && super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return scrollEnabled && super.onTouchEvent(event);
        }
    }

    private final class PhaseRow {
        final LinearLayout view;
        final TextView dragHandle;
        final EditText minutesInput;
        final EditText secondsInput;
        final EditText labelInput;
        final Button deleteButton;

        PhaseRow(PhaseDraft draft) {
            view = new LinearLayout(MainActivity.this);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);

            dragHandle = new TextView(MainActivity.this);
            dragHandle.setText("Drag");
            dragHandle.setGravity(Gravity.CENTER);
            dragHandle.setTextColor(COLOR_ACCENT);
            dragHandle.setTypeface(Typeface.DEFAULT_BOLD);
            dragHandle.setBackground(cardBackground(Color.WHITE));
            dragHandle.setOnTouchListener((dragView, event) -> handleDragTouch(this, event));
            view.addView(dragHandle, rowCellParams(56));

            minutesInput = rowInput(draft.minutes == 0 ? "" : String.valueOf(draft.minutes),
                    InputType.TYPE_CLASS_NUMBER,
                    3);
            view.addView(minutesInput, rowCellParams(62));

            secondsInput = rowInput(draft.seconds == 0 ? "" : String.valueOf(draft.seconds),
                    InputType.TYPE_CLASS_NUMBER,
                    2);
            view.addView(secondsInput, rowCellParams(62));

            labelInput = rowInput(draft.label,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    32);
            view.addView(labelInput, rowCellParams(0));

            deleteButton = new Button(MainActivity.this);
            deleteButton.setText("X");
            deleteButton.setAllCaps(false);
            deleteButton.setOnClickListener(button -> deletePhaseRow(this));
            view.addView(deleteButton, rowCellParams(48));

            TextWatcher watcher = new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    ensureTrailingEmptyRow();
                }
            };
            minutesInput.addTextChangedListener(watcher);
            secondsInput.addTextChangedListener(watcher);
            labelInput.addTextChangedListener(watcher);
        }

        boolean hasContent() {
            return minutesInput.length() > 0 || secondsInput.length() > 0 || labelInput.length() > 0;
        }
    }

    private static final class PhaseDraft {
        final int minutes;
        final int seconds;
        final String label;

        PhaseDraft(int minutes, int seconds, String label) {
            this.minutes = minutes;
            this.seconds = seconds;
            this.label = label;
        }
    }
}
