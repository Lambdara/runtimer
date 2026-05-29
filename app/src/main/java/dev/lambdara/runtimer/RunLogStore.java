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
