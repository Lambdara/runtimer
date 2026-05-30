package dev.lambdara.runtimer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RunLogStore {
    private static final String PREFS = "run_timer_store";
    private static final String KEY_LOGS = "logs";
    private static final String KEY_ACTIVE = "active_run";
    private static final String KEY_DISTANCE_MIGRATION_VERSION = "distance_migration_version";
    private static final int MAX_LOGS = 100;

    private RunLogStore() {
    }

    static JSONArray loadRuns(Context context) {
        String raw = prefs(context).getString(KEY_LOGS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    static void saveRun(Context context, JSONObject run) {
        JSONArray oldRuns = loadRuns(context);
        JSONArray newRuns = new JSONArray();
        newRuns.put(run);
        for (int i = 0; i < oldRuns.length() && i < MAX_LOGS - 1; i++) {
            newRuns.put(oldRuns.optJSONObject(i));
        }
        prefs(context).edit().putString(KEY_LOGS, newRuns.toString()).apply();
    }

    static int migrateRunDistances(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getInt(KEY_DISTANCE_MIGRATION_VERSION, 0) >= DistanceAccumulator.ALGORITHM_VERSION) {
            return 0;
        }

        JSONArray runs = loadRuns(context);
        JSONArray migratedRuns = new JSONArray();
        int migratedCount = 0;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run == null) {
                continue;
            }
            try {
                if (recalculateDistances(run)) {
                    migratedCount++;
                }
            } catch (JSONException ignored) {
            }
            migratedRuns.put(run);
        }

        preferences.edit()
                .putString(KEY_LOGS, migratedRuns.toString())
                .putInt(KEY_DISTANCE_MIGRATION_VERSION, DistanceAccumulator.ALGORITHM_VERSION)
                .apply();
        return migratedCount;
    }

    static boolean recalculateDistances(JSONObject run) throws JSONException {
        if (run.optInt("distanceAlgorithmVersion", 0) >= DistanceAccumulator.ALGORITHM_VERSION) {
            return false;
        }

        JSONArray phases = run.optJSONArray("phases");
        JSONArray routePoints = run.optJSONArray("routePoints");
        if (phases == null || routePoints == null || phases.length() == 0 || routePoints.length() < 2) {
            return false;
        }

        double[] phaseMeters = new double[phases.length()];
        JSONArray migratedRoutePoints = new JSONArray();
        DistanceAccumulator accumulator = null;
        int activePhase = -1;

        for (int i = 0; i < routePoints.length(); i++) {
            JSONObject point = routePoints.optJSONObject(i);
            if (point == null) {
                continue;
            }
            double latitude = point.optDouble("lat", Double.NaN);
            double longitude = point.optDouble("lon", Double.NaN);
            int phaseIndex = point.optInt("phase", -1);
            if (!Double.isFinite(latitude)
                    || !Double.isFinite(longitude)
                    || phaseIndex < 0
                    || phaseIndex >= phaseMeters.length) {
                continue;
            }

            if (accumulator == null
                    || activePhase != phaseIndex
                    || point.optBoolean("breakBefore", false)) {
                accumulator = new DistanceAccumulator(
                        DistanceAccumulator.DEFAULT_MAX_ACCURACY_METERS,
                        DistanceAccumulator.DEFAULT_MAX_SPEED_METERS_PER_SECOND);
                activePhase = phaseIndex;
            }

            DistanceAccumulator.AddResult result = accumulator.add(
                    latitude,
                    longitude,
                    point.optLong("time", run.optLong("startedAt")),
                    false,
                    0f);
            if (!result.recorded) {
                continue;
            }

            phaseMeters[phaseIndex] += result.addedMeters;
            JSONObject migratedPoint = new JSONObject();
            migratedPoint.put("lat", result.latitude);
            migratedPoint.put("lon", result.longitude);
            migratedPoint.put("time", point.optLong("time", run.optLong("startedAt")));
            migratedPoint.put("phase", phaseIndex);
            migratedPoint.put("breakBefore", !result.connectsFromPrevious);
            migratedRoutePoints.put(migratedPoint);
        }

        if (migratedRoutePoints.length() < 2) {
            return false;
        }

        double totalMeters = 0d;
        for (int i = 0; i < phases.length(); i++) {
            JSONObject phase = phases.optJSONObject(i);
            if (phase == null) {
                continue;
            }
            totalMeters += phaseMeters[i];
            phase.put("distanceMeters", phaseMeters[i]);
        }
        run.put("totalDistanceMeters", totalMeters);
        run.put("routePoints", migratedRoutePoints);
        run.put("distanceAlgorithmVersion", DistanceAccumulator.ALGORITHM_VERSION);
        return true;
    }

    static JSONObject findRun(Context context, long id) {
        JSONArray runs = loadRuns(context);
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null && run.optLong("id", run.optLong("startedAt")) == id) {
                return run;
            }
        }
        return null;
    }

    static void deleteRun(Context context, long id) {
        JSONArray oldRuns = loadRuns(context);
        JSONArray newRuns = new JSONArray();
        for (int i = 0; i < oldRuns.length(); i++) {
            JSONObject run = oldRuns.optJSONObject(i);
            if (run != null && run.optLong("id", run.optLong("startedAt")) != id) {
                newRuns.put(run);
            }
        }
        prefs(context).edit().putString(KEY_LOGS, newRuns.toString()).apply();
    }

    static void clearRuns(Context context) {
        prefs(context).edit().putString(KEY_LOGS, "[]").apply();
    }

    static void saveActive(Context context, JSONObject active) {
        prefs(context).edit().putString(KEY_ACTIVE, active.toString()).apply();
    }

    static JSONObject loadActive(Context context) {
        String raw = prefs(context).getString(KEY_ACTIVE, null);
        if (raw == null) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    static void clearActive(Context context) {
        prefs(context).edit().remove(KEY_ACTIVE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
