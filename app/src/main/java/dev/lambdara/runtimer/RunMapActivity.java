package dev.lambdara.runtimer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class RunMapActivity extends Activity {
    private static final String EXTRA_RUN_ID = "run_id";
    private static final int COLOR_TEXT = 0xFF161C20;
    private static final int COLOR_MUTED = 0xFF495258;
    private static final int COLOR_BORDER = 0xFFE2E6E9;
    private static final int[] PHASE_COLORS = {
            0xFF00796B,
            0xFFD84315,
            0xFF1565C0,
            0xFF6A1B9A,
            0xFF2E7D32,
            0xFFAD1457,
            0xFF455A64
    };

    static Intent intentFor(Context context, long runId) {
        return new Intent(context, RunMapActivity.class).putExtra(EXTRA_RUN_ID, runId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long runId = getIntent().getLongExtra(EXTRA_RUN_ID, -1L);
        buildUi(RunLogStore.findRun(this, runId));
    }

    private void buildUi(JSONObject run) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 248, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top);

        TextView title = new TextView(this);
        title.setText(run == null ? "Run not found" : titleFor(run));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button close = new Button(this);
        close.setText("Back");
        close.setAllCaps(false);
        close.setOnClickListener(view -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(88), dp(44)));

        if (run == null) {
            TextView missing = smallText("This run is no longer in the history log.");
            root.addView(missing, blockParams(dp(18)));
            return;
        }

        TextView summary = smallText(summaryFor(run));
        summary.setPadding(0, dp(6), 0, dp(12));
        root.addView(summary);

        RouteMapView mapView = new RouteMapView(this);
        mapView.setRun(run);
        root.addView(mapView, mapParams());

        root.addView(sectionTitle("Phases"));
        addPhaseLegend(root, run.optJSONArray("phases"));
    }

    private String titleFor(JSONObject run) {
        String date = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US)
                .format(new Date(run.optLong("startedAt")));
        return (run.optBoolean("aborted") ? "Aborted - " : "Completed - ") + date;
    }

    private String summaryFor(JSONObject run) {
        JSONArray routePoints = run.optJSONArray("routePoints");
        int pointCount = routePoints == null ? 0 : routePoints.length();
        return "Total: " + RunService.formatDistance(run.optDouble("totalDistanceMeters"))
                + " | Route samples: " + pointCount;
    }

    private void addPhaseLegend(LinearLayout root, JSONArray phases) {
        if (phases == null || phases.length() == 0) {
            TextView empty = smallText("No phase details recorded.");
            root.addView(empty, blockParams(dp(6)));
            return;
        }

        for (int i = 0; i < phases.length(); i++) {
            JSONObject phase = phases.optJSONObject(i);
            if (phase == null) {
                continue;
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            View swatch = new View(this);
            swatch.setBackground(swatchBackground(colorForPhase(i)));
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(16), dp(16));
            swatchParams.setMargins(0, 0, dp(10), 0);
            row.addView(swatch, swatchParams);

            TextView text = smallText(phaseLine(phase));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            root.addView(row, blockParams(dp(8)));
        }
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
            return title + " - " + RunService.formatDistance(meters) + " in " + RunPhase.formatClock(actual)
                    + " of " + RunPhase.formatClock(planned);
        }
        return title + " - not started";
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
        params.setMargins(0, dp(18), 0, 0);
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

    private LinearLayout.LayoutParams blockParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams mapParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(430));
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private GradientDrawable swatchBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(3));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int colorForPhase(int phaseIndex) {
        int index = Math.abs(phaseIndex) % PHASE_COLORS.length;
        return PHASE_COLORS[index];
    }

    private static final class RoutePoint {
        final double latitude;
        final double longitude;
        final int phaseIndex;
        final boolean breakBefore;

        RoutePoint(double latitude, double longitude, int phaseIndex, boolean breakBefore) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.phaseIndex = phaseIndex;
            this.breakBefore = breakBefore;
        }
    }

    private static final class RouteMapView extends View {
        private final ArrayList<RoutePoint> points = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String emptyText = "No route recorded for this run.";

        RouteMapView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setRun(JSONObject run) {
            points.clear();
            JSONArray routePoints = run.optJSONArray("routePoints");
            if (routePoints != null) {
                for (int i = 0; i < routePoints.length(); i++) {
                    JSONObject point = routePoints.optJSONObject(i);
                    if (point == null) {
                        continue;
                    }
                    double latitude = point.has("lat")
                            ? point.optDouble("lat", Double.NaN)
                            : point.optDouble("latitude", Double.NaN);
                    double longitude = point.has("lon")
                            ? point.optDouble("lon", Double.NaN)
                            : point.optDouble("longitude", Double.NaN);
                    if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                        continue;
                    }
                    points.add(new RoutePoint(
                            latitude,
                            longitude,
                            point.optInt("phase", point.optInt("phaseIndex", 0)),
                            point.optBoolean("breakBefore", points.isEmpty())));
                }
            }
            if (points.size() == 1) {
                emptyText = "Only one route point recorded.";
            } else {
                emptyText = "No route recorded for this run.";
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawBackground(canvas);
            if (points.size() < 2) {
                drawEmpty(canvas);
                return;
            }

            int width = getWidth();
            int height = getHeight();
            int padding = dp(26);
            double meanLatitudeRadians = meanLatitudeRadians();
            double[] projectedX = new double[points.size()];
            double[] projectedY = new double[points.size()];
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                double x = point.longitude * Math.cos(meanLatitudeRadians);
                double y = point.latitude;
                projectedX[i] = x;
                projectedY[i] = y;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }

            double spanX = Math.max(maxX - minX, 0.00001d);
            double spanY = Math.max(maxY - minY, 0.00001d);
            double scale = Math.min(
                    Math.max(1d, width - padding * 2d) / spanX,
                    Math.max(1d, height - padding * 2d) / spanY);
            double centerX = (minX + maxX) / 2d;
            double centerY = (minY + maxY) / 2d;

            float[] screenX = new float[points.size()];
            float[] screenY = new float[points.size()];
            for (int i = 0; i < points.size(); i++) {
                screenX[i] = (float) (width / 2d + (projectedX[i] - centerX) * scale);
                screenY[i] = (float) (height / 2d - (projectedY[i] - centerY) * scale);
            }

            drawGrid(canvas, padding);
            drawRoute(canvas, screenX, screenY);
            drawPhaseBreaks(canvas, screenX, screenY);
            drawMarker(canvas, screenX[0], screenY[0], 0xFF1B7F3A, "Start");
            int last = points.size() - 1;
            drawMarker(canvas, screenX[last], screenY[last], 0xFFB3261E, "End");
        }

        private void drawBackground(Canvas canvas) {
            RectF bounds = new RectF(0.5f, 0.5f, getWidth() - 0.5f, getHeight() - 0.5f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(bounds, dp(8), dp(8), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(COLOR_BORDER);
            canvas.drawRoundRect(bounds, dp(8), dp(8), paint);
        }

        private void drawEmpty(Canvas canvas) {
            textPaint.setColor(COLOR_MUTED);
            textPaint.setTextSize(sp(16));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float y = getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(emptyText, getWidth() / 2f, y, textPaint);
        }

        private void drawGrid(Canvas canvas, int padding) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFFE8ECEF);
            for (int i = 1; i <= 3; i++) {
                float x = padding + (getWidth() - padding * 2f) * i / 4f;
                float y = padding + (getHeight() - padding * 2f) * i / 4f;
                canvas.drawLine(x, padding, x, getHeight() - padding, paint);
                canvas.drawLine(padding, y, getWidth() - padding, y, paint);
            }
        }

        private void drawRoute(Canvas canvas, float[] screenX, float[] screenY) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            for (int i = 1; i < points.size(); i++) {
                RoutePoint point = points.get(i);
                if (point.breakBefore) {
                    continue;
                }
                paint.setColor(colorForPhase(point.phaseIndex));
                canvas.drawLine(screenX[i - 1], screenY[i - 1], screenX[i], screenY[i], paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawPhaseBreaks(Canvas canvas, float[] screenX, float[] screenY) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 1; i < points.size(); i++) {
                if (points.get(i).phaseIndex == points.get(i - 1).phaseIndex) {
                    continue;
                }
                paint.setColor(Color.WHITE);
                canvas.drawCircle(screenX[i], screenY[i], dp(6), paint);
                paint.setColor(colorForPhase(points.get(i).phaseIndex));
                canvas.drawCircle(screenX[i], screenY[i], dp(4), paint);
            }
        }

        private void drawMarker(Canvas canvas, float x, float y, int color, String label) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(8), paint);
            paint.setColor(color);
            canvas.drawCircle(x, y, dp(5), paint);

            textPaint.setColor(COLOR_TEXT);
            textPaint.setTextSize(sp(12));
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.LEFT);
            float labelX = clamp(x + dp(8), dp(8), getWidth() - textPaint.measureText(label) - dp(8));
            float labelY = clamp(y - dp(8), dp(18), getHeight() - dp(8));
            canvas.drawText(label, labelX, labelY, textPaint);
        }

        private double meanLatitudeRadians() {
            double sum = 0d;
            for (RoutePoint point : points) {
                sum += point.latitude;
            }
            return Math.toRadians(sum / points.size());
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        private float sp(int value) {
            return TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    value,
                    getResources().getDisplayMetrics());
        }
    }
}
