package dev.lambdara.runtimer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunPhase {
    private static final Pattern UNIT_DURATION = Pattern.compile(
            "^\\s*(\\d+(?:[\\.,]\\d+)?)\\s*(h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\\b\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK_DURATION = Pattern.compile(
            "^\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\s*(.*?)\\s*$");

    final long durationMillis;
    final String label;

    RunPhase(long durationMillis, String label) {
        this.durationMillis = durationMillis;
        this.label = label == null ? "" : label.trim();
    }

    String title() {
        if (label.isEmpty()) {
            return formatHumanDuration(durationMillis);
        }
        return formatHumanDuration(durationMillis) + " " + label;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("durationMillis", durationMillis);
        json.put("label", label);
        return json;
    }

    static RunPhase fromJson(JSONObject json) {
        return new RunPhase(json.optLong("durationMillis"), json.optString("label"));
    }

    static String toJsonString(List<RunPhase> phases) throws JSONException {
        JSONArray array = new JSONArray();
        for (RunPhase phase : phases) {
            array.put(phase.toJson());
        }
        return array.toString();
    }

    static ArrayList<RunPhase> listFromJson(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        ArrayList<RunPhase> phases = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            phases.add(fromJson(array.getJSONObject(i)));
        }
        return phases;
    }

    static ArrayList<RunPhase> parseSchedule(String schedule) {
        ArrayList<RunPhase> phases = new ArrayList<>();
        String[] rawParts = schedule.split("[;\\n]+");
        int phaseNumber = 1;
        for (String rawPart : rawParts) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }

            RunPhase phase = parseOne(part, phaseNumber);
            phases.add(phase);
            phaseNumber++;
        }

        if (phases.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one phase, for example: 10 minutes easy; 20 minutes hard");
        }
        return phases;
    }

    private static RunPhase parseOne(String part, int phaseNumber) {
        Matcher clock = CLOCK_DURATION.matcher(part);
        if (clock.matches()) {
            long millis;
            if (clock.group(1) == null) {
                int minutes = Integer.parseInt(clock.group(2));
                int seconds = Integer.parseInt(clock.group(3));
                millis = ((minutes * 60L) + seconds) * 1000L;
            } else {
                int hours = Integer.parseInt(clock.group(1));
                int minutes = Integer.parseInt(clock.group(2));
                int seconds = Integer.parseInt(clock.group(3));
                millis = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L;
            }
            return checkedPhase(millis, clock.group(4), phaseNumber);
        }

        Matcher unit = UNIT_DURATION.matcher(part);
        if (unit.matches()) {
            double amount = Double.parseDouble(unit.group(1).replace(',', '.'));
            String unitName = unit.group(2).toLowerCase(Locale.US);
            double multiplier;
            if (unitName.startsWith("h")) {
                multiplier = 3600_000d;
            } else if (unitName.startsWith("m")) {
                multiplier = 60_000d;
            } else {
                multiplier = 1000d;
            }
            return checkedPhase(Math.round(amount * multiplier), unit.group(3), phaseNumber);
        }

        throw new IllegalArgumentException("Could not read phase: " + part);
    }

    private static RunPhase checkedPhase(long durationMillis, String label, int phaseNumber) {
        if (durationMillis < 1000L) {
            throw new IllegalArgumentException("Phase " + phaseNumber + " must be at least 1 second.");
        }
        String cleanLabel = label == null ? "" : label.trim();
        if (cleanLabel.isEmpty()) {
            cleanLabel = "phase " + phaseNumber;
        }
        return new RunPhase(durationMillis, cleanLabel);
    }

    static String formatClock(long millis) {
        long totalSeconds = Math.max(0L, Math.round(millis / 1000d));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    static String formatHumanDuration(long millis) {
        long totalSeconds = Math.max(1L, Math.round(millis / 1000d));
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            if (seconds == 0L) {
                return String.format(Locale.US, "%dh %02dm", hours, minutes);
            }
            return String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds);
        }
        if (minutes > 0L) {
            if (seconds == 0L) {
                return String.format(Locale.US, "%d min", minutes);
            }
            return String.format(Locale.US, "%d min %02d sec", minutes, seconds);
        }
        return String.format(Locale.US, "%d sec", seconds);
    }
}
