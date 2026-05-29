package dev.lambdara.runtimer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class RunService extends Service {
    static final String ACTION_START = "dev.lambdara.runtimer.action.START";
    static final String ACTION_STOP = "dev.lambdara.runtimer.action.STOP";
    static final String ACTION_DISMISS_ALERT = "dev.lambdara.runtimer.action.DISMISS_ALERT";
    static final String ACTION_STATUS_CHANGED = "dev.lambdara.runtimer.action.STATUS_CHANGED";
    static final String EXTRA_PHASES_JSON = "phases_json";
    static final String EXTRA_SCHEDULE_TEXT = "schedule_text";

    static final int STATE_IDLE = 0;
    static final int STATE_RUNNING = 1;
    static final int STATE_AWAITING_DISMISS = 2;

    private static final String TAG = "RunService";
    private static final String CHANNEL_RUN = "run_status";
    private static final String CHANNEL_ALERT = "phase_alerts";
    private static final int NOTIFICATION_ID = 41;
    private static final long LOCATION_INTERVAL_MS = 1000L;
    private static final float LOCATION_MIN_METERS = 0f;
    private static final float MAX_GOOD_ACCURACY_METERS = 65f;
    private static final double MAX_REASONABLE_SPEED_METERS_PER_SECOND = 14.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DistanceAccumulator distanceAccumulator = new DistanceAccumulator(
            MAX_GOOD_ACCURACY_METERS,
            MAX_REASONABLE_SPEED_METERS_PER_SECOND);
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleLocation(location);
        }
        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };
    private final Runnable phaseEndRunnable = this::onPhaseTimeExpired;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
    };

    private ArrayList<RunPhase> phases = new ArrayList<>();
    private double[] phaseMeters = new double[0];
    private long[] phaseActualMillis = new long[0];
    private boolean[] phaseFinished = new boolean[0];
    private int state = STATE_IDLE;
    private int phaseIndex = 0;
    private long runStartedWallMillis = 0L;
    private long runStartedElapsedMillis = 0L;
    private long phaseStartedElapsedMillis = 0L;
    private long phaseEndsElapsedMillis = 0L;
    private long lastStatusWriteElapsedMillis = 0L;
    private String scheduleText = "";
    private LocationManager locationManager;
    private Location lastKnownLocation;
    private PowerManager.WakeLock wakeLock;
    private Ringtone ringtone;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private Vibrator vibrator;
    private boolean resumeMediaAfterAlert;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        vibrator = getSystemService(Vibrator.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            handleStart(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopRun(true);
        } else if (ACTION_DISMISS_ALERT.equals(action)) {
            dismissAlertAndContinue();
        } else if (intent == null) {
            restoreIfPossible();
        } else {
            writeActiveStatus(true);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        stopAlertSound();
        releaseWakeLock();
        super.onDestroy();
    }

    private void handleStart(Intent intent) {
        try {
            ArrayList<RunPhase> requestedPhases = RunPhase.listFromJson(intent.getStringExtra(EXTRA_PHASES_JSON));
            if (requestedPhases.isEmpty()) {
                return;
            }
            if (state != STATE_IDLE) {
                stopRun(true);
            }

            phases = requestedPhases;
            phaseMeters = new double[phases.size()];
            phaseActualMillis = new long[phases.size()];
            phaseFinished = new boolean[phases.size()];
            phaseIndex = 0;
            resumeMediaAfterAlert = false;
            scheduleText = intent.getStringExtra(EXTRA_SCHEDULE_TEXT);
            if (scheduleText == null) {
                scheduleText = "";
            }
            runStartedWallMillis = System.currentTimeMillis();
            runStartedElapsedMillis = SystemClock.elapsedRealtime();

            startForegroundWith(buildOngoingNotification());
            acquireWakeLock();
            startLocationUpdates();
            startPhase(0);
        } catch (JSONException | IllegalArgumentException exception) {
            Log.e(TAG, "Could not start run", exception);
            stopSelf();
        }
    }

    private void restoreIfPossible() {
        JSONObject active = RunLogStore.loadActive(this);
        if (active == null || !active.optBoolean("active")) {
            stopSelf();
            return;
        }
        try {
            restoreFromStatus(active);
            startForegroundWith(state == STATE_AWAITING_DISMISS ? buildAlertNotification() : buildOngoingNotification());
            acquireWakeLock();
            startLocationUpdates();
            if (state == STATE_RUNNING) {
                long delay = Math.max(0L, phaseEndsElapsedMillis - SystemClock.elapsedRealtime());
                handler.removeCallbacks(phaseEndRunnable);
                handler.postDelayed(phaseEndRunnable, delay);
            } else if (state == STATE_AWAITING_DISMISS) {
                startAlertSound();
            }
        } catch (JSONException exception) {
            Log.e(TAG, "Could not restore active run", exception);
            RunLogStore.clearActive(this);
            stopSelf();
        }
    }

    private void restoreFromStatus(JSONObject active) throws JSONException {
        phases = RunPhase.listFromJson(active.getJSONArray("phases").toString());
        phaseMeters = doubleArray(active.getJSONArray("phaseMeters"), phases.size());
        phaseActualMillis = longArray(active.getJSONArray("phaseActualMillis"), phases.size());
        phaseFinished = booleanArray(active.getJSONArray("phaseFinished"), phases.size());
        state = active.optInt("state", STATE_IDLE);
        phaseIndex = active.optInt("phaseIndex", 0);
        runStartedWallMillis = active.optLong("runStartedWallMillis", System.currentTimeMillis());
        runStartedElapsedMillis = active.optLong("runStartedElapsedMillis", SystemClock.elapsedRealtime());
        phaseStartedElapsedMillis = active.optLong("phaseStartedElapsedMillis", SystemClock.elapsedRealtime());
        phaseEndsElapsedMillis = active.optLong("phaseEndsElapsedMillis", SystemClock.elapsedRealtime());
        scheduleText = active.optString("scheduleText", "");
        resumeMediaAfterAlert = active.optBoolean("resumeMediaAfterAlert", false);
    }

    private void startPhase(int index) {
        stopAlertSound(false);
        handler.removeCallbacks(phaseEndRunnable);
        phaseIndex = index;
        state = STATE_RUNNING;
        phaseStartedElapsedMillis = SystemClock.elapsedRealtime();
        phaseEndsElapsedMillis = phaseStartedElapsedMillis + phases.get(index).durationMillis;
        phaseActualMillis[index] = 0L;
        phaseFinished[index] = false;
        distanceAccumulator.reset();
        handler.postDelayed(phaseEndRunnable, phases.get(index).durationMillis);
        writeActiveStatus(true);
        notifyForeground(buildOngoingNotification());
    }

    private void onPhaseTimeExpired() {
        if (state != STATE_RUNNING || phaseIndex < 0 || phaseIndex >= phases.size()) {
            return;
        }
        phaseActualMillis[phaseIndex] = phases.get(phaseIndex).durationMillis;
        phaseFinished[phaseIndex] = true;
        state = STATE_AWAITING_DISMISS;
        distanceAccumulator.reset();
        resumeMediaAfterAlert = audioManager != null && audioManager.isMusicActive();
        writeActiveStatus(true);
        startAlertSound();
        Notification notification = buildAlertNotification();
        notifyForeground(notification);
        launchAlertActivity();
    }

    private void dismissAlertAndContinue() {
        if (state != STATE_AWAITING_DISMISS) {
            return;
        }
        stopAlertSound();
        if (phaseIndex >= phases.size() - 1) {
            stopRun(false);
        } else {
            startPhase(phaseIndex + 1);
        }
    }

    private void stopRun(boolean aborted) {
        if (state == STATE_IDLE || phases.isEmpty()) {
            cleanupStoppedRun();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (state == STATE_RUNNING && phaseIndex >= 0 && phaseIndex < phases.size()) {
            long elapsed = Math.max(0L, now - phaseStartedElapsedMillis);
            phaseActualMillis[phaseIndex] = Math.min(elapsed, phases.get(phaseIndex).durationMillis);
            phaseFinished[phaseIndex] = false;
        }

        saveRunLog(aborted);
        cleanupStoppedRun();
    }

    private void cleanupStoppedRun() {
        state = STATE_IDLE;
        handler.removeCallbacks(phaseEndRunnable);
        stopLocationUpdates();
        stopAlertSound();
        releaseWakeLock();
        RunLogStore.clearActive(this);
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void saveRunLog(boolean aborted) {
        try {
            JSONObject log = new JSONObject();
            long endedAt = System.currentTimeMillis();
            log.put("id", runStartedWallMillis);
            log.put("startedAt", runStartedWallMillis);
            log.put("endedAt", endedAt);
            log.put("title", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(runStartedWallMillis)));
            log.put("scheduleText", scheduleText);
            log.put("aborted", aborted);
            log.put("abortedPhaseIndex", aborted ? phaseIndex : -1);
            log.put("abortedWhileAwaitingDismiss", aborted && state == STATE_AWAITING_DISMISS);
            if (aborted && lastKnownLocation != null) {
                log.put("abortedLatitude", lastKnownLocation.getLatitude());
                log.put("abortedLongitude", lastKnownLocation.getLongitude());
            }

            double totalMeters = 0d;
            JSONArray phaseLogs = new JSONArray();
            for (int i = 0; i < phases.size(); i++) {
                totalMeters += phaseMeters[i];
                JSONObject phase = new JSONObject();
                phase.put("label", phases.get(i).label);
                phase.put("title", phases.get(i).title());
                phase.put("plannedDurationMillis", phases.get(i).durationMillis);
                phase.put("actualDurationMillis", phaseActualMillis[i]);
                phase.put("distanceMeters", phaseMeters[i]);
                phase.put("completed", phaseFinished[i]);
                phaseLogs.put(phase);
            }
            log.put("totalDistanceMeters", totalMeters);
            log.put("phases", phaseLogs);
            RunLogStore.saveRun(this, log);
        } catch (JSONException exception) {
            Log.e(TAG, "Could not save run log", exception);
        }
    }

    private void startLocationUpdates() {
        if (locationManager == null || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestUpdates(LocationManager.GPS_PROVIDER);
        requestUpdates(LocationManager.NETWORK_PROVIDER);
    }

    private void requestUpdates(String provider) {
        try {
            if (locationManager.isProviderEnabled(provider)) {
                locationManager.requestLocationUpdates(
                        provider,
                        LOCATION_INTERVAL_MS,
                        LOCATION_MIN_METERS,
                        locationListener,
                        Looper.getMainLooper());
            }
        } catch (IllegalArgumentException | SecurityException exception) {
            Log.w(TAG, "Location provider unavailable: " + provider, exception);
        }
    }

    private void stopLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
        }
    }

    private void handleLocation(Location location) {
        if (location == null) {
            return;
        }
        lastKnownLocation = location;
        if (state != STATE_RUNNING || phaseIndex < 0 || phaseIndex >= phaseMeters.length) {
            return;
        }
        phaseMeters[phaseIndex] += distanceAccumulator.add(
                location.getLatitude(),
                location.getLongitude(),
                location.getTime(),
                location.hasAccuracy(),
                location.getAccuracy());
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatusWriteElapsedMillis >= 3000L) {
            writeActiveStatus(false);
        }
    }

    private void writeActiveStatus(boolean broadcast) {
        if (state == STATE_IDLE) {
            return;
        }
        try {
            JSONObject active = new JSONObject();
            active.put("active", true);
            active.put("state", state);
            active.put("phaseIndex", phaseIndex);
            active.put("phaseStartedElapsedMillis", phaseStartedElapsedMillis);
            active.put("phaseEndsElapsedMillis", phaseEndsElapsedMillis);
            active.put("runStartedWallMillis", runStartedWallMillis);
            active.put("runStartedElapsedMillis", runStartedElapsedMillis);
            active.put("scheduleText", scheduleText);
            active.put("resumeMediaAfterAlert", resumeMediaAfterAlert);
            active.put("phases", phasesJsonArray());
            active.put("phaseMeters", doubleJsonArray(phaseMeters));
            active.put("phaseActualMillis", longJsonArray(phaseActualMillis));
            active.put("phaseFinished", booleanJsonArray(phaseFinished));
            RunLogStore.saveActive(this, active);
            lastStatusWriteElapsedMillis = SystemClock.elapsedRealtime();
            if (broadcast) {
                sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName()));
            }
        } catch (JSONException exception) {
            Log.e(TAG, "Could not write active status", exception);
        }
    }

    private JSONArray phasesJsonArray() throws JSONException {
        JSONArray array = new JSONArray();
        for (RunPhase phase : phases) {
            array.put(phase.toJson());
        }
        return array;
    }

    private static JSONArray doubleJsonArray(double[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (double value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONArray longJsonArray(long[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (long value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONArray booleanJsonArray(boolean[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (boolean value : values) {
            array.put(value);
        }
        return array;
    }

    private static double[] doubleArray(JSONArray array, int size) {
        double[] values = new double[size];
        for (int i = 0; i < size && i < array.length(); i++) {
            values[i] = array.optDouble(i, 0d);
        }
        return values;
    }

    private static long[] longArray(JSONArray array, int size) {
        long[] values = new long[size];
        for (int i = 0; i < size && i < array.length(); i++) {
            values[i] = array.optLong(i, 0L);
        }
        return values;
    }

    private static boolean[] booleanArray(JSONArray array, int size) {
        boolean[] values = new boolean[size];
        for (int i = 0; i < size && i < array.length(); i++) {
            values[i] = array.optBoolean(i, false);
        }
        return values;
    }

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel runChannel = new NotificationChannel(
                CHANNEL_RUN,
                "Run status",
                NotificationManager.IMPORTANCE_LOW);
        runChannel.setDescription("Shows that the run timer is active.");
        runChannel.setSound(null, null);
        manager.createNotificationChannel(runChannel);

        NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT,
                "Phase alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription("Shows phase transition alerts.");
        alertChannel.setBypassDnd(true);
        manager.createNotificationChannel(alertChannel);
    }

    private Notification buildOngoingNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 1, contentIntent, pendingIntentFlags());
        Intent stopIntent = new Intent(this, RunService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 2, stopIntent, pendingIntentFlags());

        String title = "Run timer active";
        String text = "Preparing run";
        if (state == STATE_RUNNING && phaseIndex >= 0 && phaseIndex < phases.size()) {
            long remaining = Math.max(0L, phaseEndsElapsedMillis - SystemClock.elapsedRealtime());
            title = phases.get(phaseIndex).title();
            text = "Remaining " + RunPhase.formatClock(remaining) + " - " + formatDistance(totalDistanceMeters());
        } else if (state == STATE_AWAITING_DISMISS) {
            title = "Phase finished";
            text = alertLineOne();
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_RUN);
        builder.setSmallIcon(R.drawable.ic_stat_run_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_run_timer),
                        "End run",
                        stopPendingIntent).build());
        builder.setCategory(Notification.CATEGORY_SERVICE);
        builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        return builder.build();
    }

    private Notification buildAlertNotification() {
        Intent alertIntent = PhaseAlertActivity.intentFor(this);
        PendingIntent alertPendingIntent = PendingIntent.getActivity(this, 3, alertIntent, pendingIntentFlags());
        Intent dismissIntent = new Intent(this, RunService.class).setAction(ACTION_DISMISS_ALERT);
        PendingIntent dismissPendingIntent = PendingIntent.getService(this, 4, dismissIntent, pendingIntentFlags());
        Intent stopIntent = new Intent(this, RunService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 5, stopIntent, pendingIntentFlags());

        String actionLabel = phaseIndex >= phases.size() - 1 ? "Finish" : "Start next";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ALERT);
        builder.setSmallIcon(R.drawable.ic_stat_run_timer)
                .setContentTitle("Phase finished")
                .setContentText(alertLineOne())
                .setStyle(new Notification.BigTextStyle().bigText(alertLineOne() + "\n" + alertLineTwo()))
                .setContentIntent(alertPendingIntent)
                .setFullScreenIntent(alertPendingIntent, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_run_timer),
                        actionLabel,
                        dismissPendingIntent).build());
        if (phaseIndex < phases.size() - 1) {
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_run_timer),
                    "End run",
                    stopPendingIntent).build());
        }
        builder.setCategory(Notification.CATEGORY_ALARM);
        builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        return builder.build();
    }

    private String alertLineOne() {
        if (phaseIndex < 0 || phaseIndex >= phases.size()) {
            return "Finished current phase";
        }
        return "Finished: " + phases.get(phaseIndex).title();
    }

    private String alertLineTwo() {
        if (phaseIndex < phases.size() - 1) {
            return "Next: " + phases.get(phaseIndex + 1).title();
        }
        return "Run complete";
    }

    private void startForegroundWith(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyForeground(Notification notification) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void launchAlertActivity() {
        Intent alertIntent = PhaseAlertActivity.intentFor(this);
        alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(alertIntent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not launch alert activity directly; full-screen notification remains available.", exception);
        }
    }

    private void startAlertSound() {
        stopAlertSound(false);
        if (!resumeMediaAfterAlert && audioManager != null) {
            resumeMediaAfterAlert = audioManager.isMusicActive();
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        if (audioManager != null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        }

        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }
        if (uri != null) {
            ringtone = RingtoneManager.getRingtone(this, uri);
            if (ringtone != null) {
                ringtone.setAudioAttributes(attributes);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.setLooping(true);
                }
                ringtone.play();
            }
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0L, 350L, 250L, 350L, 900L}, 0));
        }
    }

    private void stopAlertSound() {
        stopAlertSound(true);
    }

    private void stopAlertSound(boolean resumeInterruptedMedia) {
        boolean shouldResumeMedia = resumeInterruptedMedia && resumeMediaAfterAlert;
        if (ringtone != null) {
            if (ringtone.isPlaying()) {
                ringtone.stop();
            }
            ringtone = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (audioManager != null) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        }
        focusRequest = null;
        if (resumeInterruptedMedia) {
            resumeMediaAfterAlert = false;
        }
        if (shouldResumeMedia) {
            handler.postDelayed(this::resumeInterruptedMedia, 300L);
        }
    }

    private void resumeInterruptedMedia() {
        if (audioManager == null || audioManager.isMusicActive()) {
            return;
        }
        long eventTime = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        KeyEvent up = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunTimer:ActiveRun");
        wakeLock.acquire(wakeLockTimeoutMillis());
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private long wakeLockTimeoutMillis() {
        long plannedRemaining = 0L;
        if (!phases.isEmpty()) {
            long now = SystemClock.elapsedRealtime();
            if (state == STATE_RUNNING && phaseIndex >= 0 && phaseIndex < phases.size()) {
                plannedRemaining += Math.max(0L, phaseEndsElapsedMillis - now);
                for (int i = phaseIndex + 1; i < phases.size(); i++) {
                    plannedRemaining += phases.get(i).durationMillis;
                }
            } else {
                for (int i = Math.max(0, phaseIndex); i < phases.size(); i++) {
                    plannedRemaining += phases.get(i).durationMillis;
                }
            }
        }
        return Math.max(30L * 60L * 1000L, plannedRemaining + 60L * 60L * 1000L);
    }

    private double totalDistanceMeters() {
        double total = 0d;
        for (double meters : phaseMeters) {
            total += meters;
        }
        return total;
    }

    static String formatDistance(double meters) {
        if (meters >= 1000d) {
            return String.format(Locale.US, "%.2f km", meters / 1000d);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    static boolean isProbablyRunning(Context context) {
        JSONObject active = RunLogStore.loadActive(context);
        return active != null && active.optBoolean("active");
    }

    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
