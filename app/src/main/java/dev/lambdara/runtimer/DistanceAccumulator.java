package dev.lambdara.runtimer;

final class DistanceAccumulator {
    private static final double EARTH_RADIUS_METERS = 6371008.8d;

    private final float maxAccuracyMeters;
    private final double maxSpeedMetersPerSecond;
    private Sample lastAccepted;

    DistanceAccumulator(float maxAccuracyMeters, double maxSpeedMetersPerSecond) {
        this.maxAccuracyMeters = maxAccuracyMeters;
        this.maxSpeedMetersPerSecond = maxSpeedMetersPerSecond;
    }

    void reset() {
        lastAccepted = null;
    }

    AddResult add(double latitude, double longitude, long timeMillis, boolean hasAccuracy, float accuracyMeters) {
        if (hasAccuracy && accuracyMeters > maxAccuracyMeters) {
            return AddResult.rejected();
        }

        Sample current = new Sample(latitude, longitude, timeMillis);
        double addedMeters = 0d;
        boolean connectsFromPrevious = false;
        if (lastAccepted != null) {
            double distanceMeters = distanceMeters(lastAccepted, current);
            long deltaMillis = Math.max(0L, current.timeMillis - lastAccepted.timeMillis);
            double speedMetersPerSecond = deltaMillis == 0L ? 0d : distanceMeters / (deltaMillis / 1000d);
            if (distanceMeters >= 0d
                    && (deltaMillis == 0L
                    || speedMetersPerSecond <= maxSpeedMetersPerSecond
                    || distanceMeters < 20d)) {
                addedMeters = distanceMeters;
                connectsFromPrevious = true;
            }
        }
        lastAccepted = current;
        return new AddResult(addedMeters, true, connectsFromPrevious);
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = lat2Rad - lat1Rad;
        double deltaLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(deltaLat / 2d);
        double sinLon = Math.sin(deltaLon / 2d);
        double a = sinLat * sinLat + Math.cos(lat1Rad) * Math.cos(lat2Rad) * sinLon * sinLon;
        double c = 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
        return EARTH_RADIUS_METERS * c;
    }

    private static double distanceMeters(Sample first, Sample second) {
        return distanceMeters(first.latitude, first.longitude, second.latitude, second.longitude);
    }

    static final class AddResult {
        final double addedMeters;
        final boolean recorded;
        final boolean connectsFromPrevious;

        AddResult(double addedMeters, boolean recorded, boolean connectsFromPrevious) {
            this.addedMeters = addedMeters;
            this.recorded = recorded;
            this.connectsFromPrevious = connectsFromPrevious;
        }

        static AddResult rejected() {
            return new AddResult(0d, false, false);
        }
    }

    private static final class Sample {
        final double latitude;
        final double longitude;
        final long timeMillis;

        Sample(double latitude, double longitude, long timeMillis) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.timeMillis = timeMillis;
        }
    }
}
