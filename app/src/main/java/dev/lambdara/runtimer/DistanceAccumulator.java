package dev.lambdara.runtimer;

final class DistanceAccumulator {
    static final int ALGORITHM_VERSION = 3;
    static final float DEFAULT_MAX_ACCURACY_METERS = 40f;
    static final double DEFAULT_MAX_SPEED_METERS_PER_SECOND = 14d;

    private static final double EARTH_RADIUS_METERS = 6371008.8d;
    private static final double DEFAULT_ACCURACY_METERS = 25d;
    private static final double MIN_ACCURACY_METERS = 4d;
    private static final double EFFECTIVE_ACCURACY_SCALE = 1.6d;
    private static final double ACCELERATION_NOISE_METERS_PER_SECOND2 = 1.0d;
    private static final double INITIAL_VELOCITY_VARIANCE = 9d;
    private static final double MIN_COUNTED_SEGMENT_METERS = 3d;
    private static final double MAX_NOISE_GATE_METERS = 12d;
    private static final double NOISE_GATE_ACCURACY_FRACTION = 0.85d;
    private static final double OUTLIER_MIN_DISTANCE_METERS = 20d;

    private final float maxAccuracyMeters;
    private final double maxSpeedMetersPerSecond;
    private LocalProjection projection;
    private AxisKalmanFilter xFilter;
    private AxisKalmanFilter yFilter;
    private MeterSample lastRaw;
    private MeterSample lastCounted;
    private long lastFilterTimeMillis;

    DistanceAccumulator(float maxAccuracyMeters, double maxSpeedMetersPerSecond) {
        this.maxAccuracyMeters = maxAccuracyMeters;
        this.maxSpeedMetersPerSecond = maxSpeedMetersPerSecond;
    }

    void reset() {
        projection = null;
        xFilter = null;
        yFilter = null;
        lastRaw = null;
        lastCounted = null;
        lastFilterTimeMillis = 0L;
    }

    AddResult add(double latitude, double longitude, long timeMillis, boolean hasAccuracy, float accuracyMeters) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return AddResult.rejected();
        }
        if (hasAccuracy && accuracyMeters > maxAccuracyMeters) {
            return AddResult.rejected();
        }

        double accuracy = normalizedAccuracy(hasAccuracy, accuracyMeters);
        if (projection == null) {
            return initialize(latitude, longitude, timeMillis, accuracy);
        }

        MeterSample raw = new MeterSample(
                projection.x(latitude, longitude),
                projection.y(latitude),
                timeMillis);
        if (isOutlier(raw)) {
            return initialize(latitude, longitude, timeMillis, accuracy);
        }

        double dtSeconds = Math.max(0d, (timeMillis - lastFilterTimeMillis) / 1000d);
        xFilter.predict(dtSeconds);
        yFilter.predict(dtSeconds);
        double measurementVariance = accuracy * accuracy;
        xFilter.update(raw.x, measurementVariance);
        yFilter.update(raw.y, measurementVariance);
        lastFilterTimeMillis = timeMillis;
        lastRaw = raw;

        MeterSample filtered = new MeterSample(xFilter.position, yFilter.position, timeMillis);
        double movementGateMeters = noiseGateMeters(accuracy);
        double rawMetersFromLastCounted = lastCounted == null ? 0d : distanceMeters(lastCounted, raw);
        double segmentMeters = lastCounted == null
                ? 0d
                : Math.min(distanceMeters(lastCounted, filtered), rawMetersFromLastCounted);
        if (lastCounted != null && rawMetersFromLastCounted < movementGateMeters) {
            return AddResult.notRecorded(currentSpeedMetersPerSecond());
        }

        lastCounted = filtered;
        return AddResult.recorded(
                segmentMeters,
                segmentMeters > 0d,
                projection.latitude(filtered.y),
                projection.longitude(filtered.x),
                currentSpeedMetersPerSecond());
    }

    private AddResult initialize(double latitude, double longitude, long timeMillis, double accuracy) {
        projection = new LocalProjection(latitude, longitude);
        double measurementVariance = accuracy * accuracy;
        xFilter = new AxisKalmanFilter(0d, measurementVariance, INITIAL_VELOCITY_VARIANCE);
        yFilter = new AxisKalmanFilter(0d, measurementVariance, INITIAL_VELOCITY_VARIANCE);
        lastRaw = new MeterSample(0d, 0d, timeMillis);
        lastCounted = new MeterSample(0d, 0d, timeMillis);
        lastFilterTimeMillis = timeMillis;
        return AddResult.recorded(0d, false, latitude, longitude, 0d);
    }

    private boolean isOutlier(MeterSample raw) {
        if (lastRaw == null) {
            return false;
        }
        double distanceMeters = distanceMeters(lastRaw, raw);
        long deltaMillis = Math.max(0L, raw.timeMillis - lastRaw.timeMillis);
        if (deltaMillis == 0L) {
            return distanceMeters >= OUTLIER_MIN_DISTANCE_METERS;
        }
        double speedMetersPerSecond = distanceMeters / (deltaMillis / 1000d);
        return distanceMeters >= OUTLIER_MIN_DISTANCE_METERS
                && speedMetersPerSecond > maxSpeedMetersPerSecond;
    }

    private static double normalizedAccuracy(boolean hasAccuracy, float accuracyMeters) {
        double reportedAccuracy;
        if (!hasAccuracy || accuracyMeters <= 0f || !Float.isFinite(accuracyMeters)) {
            reportedAccuracy = DEFAULT_ACCURACY_METERS;
        } else {
            reportedAccuracy = Math.max(MIN_ACCURACY_METERS, accuracyMeters);
        }
        return reportedAccuracy * EFFECTIVE_ACCURACY_SCALE;
    }

    private static double noiseGateMeters(double accuracyMeters) {
        return Math.min(
                MAX_NOISE_GATE_METERS,
                Math.max(MIN_COUNTED_SEGMENT_METERS, accuracyMeters * NOISE_GATE_ACCURACY_FRACTION));
    }

    private double currentSpeedMetersPerSecond() {
        if (xFilter == null || yFilter == null) {
            return Double.NaN;
        }
        return Math.min(maxSpeedMetersPerSecond, Math.hypot(xFilter.velocity, yFilter.velocity));
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

    private static double distanceMeters(MeterSample first, MeterSample second) {
        return Math.hypot(second.x - first.x, second.y - first.y);
    }

    static final class AddResult {
        final double addedMeters;
        final boolean recorded;
        final boolean connectsFromPrevious;
        final double latitude;
        final double longitude;
        final double currentSpeedMetersPerSecond;

        AddResult(
                double addedMeters,
                boolean recorded,
                boolean connectsFromPrevious,
                double latitude,
                double longitude,
                double currentSpeedMetersPerSecond) {
            this.addedMeters = addedMeters;
            this.recorded = recorded;
            this.connectsFromPrevious = connectsFromPrevious;
            this.latitude = latitude;
            this.longitude = longitude;
            this.currentSpeedMetersPerSecond = currentSpeedMetersPerSecond;
        }

        static AddResult recorded(
                double addedMeters,
                boolean connectsFromPrevious,
                double latitude,
                double longitude,
                double currentSpeedMetersPerSecond) {
            return new AddResult(
                    addedMeters,
                    true,
                    connectsFromPrevious,
                    latitude,
                    longitude,
                    currentSpeedMetersPerSecond);
        }

        static AddResult notRecorded(double currentSpeedMetersPerSecond) {
            return new AddResult(
                    0d,
                    false,
                    false,
                    Double.NaN,
                    Double.NaN,
                    currentSpeedMetersPerSecond);
        }

        static AddResult rejected() {
            return new AddResult(0d, false, false, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private static final class LocalProjection {
        private final double originLatitude;
        private final double originLongitude;
        private final double originLatitudeRadians;

        LocalProjection(double originLatitude, double originLongitude) {
            this.originLatitude = originLatitude;
            this.originLongitude = originLongitude;
            this.originLatitudeRadians = Math.toRadians(originLatitude);
        }

        double x(double latitude, double longitude) {
            return EARTH_RADIUS_METERS
                    * Math.toRadians(longitude - originLongitude)
                    * Math.cos(originLatitudeRadians);
        }

        double y(double latitude) {
            return EARTH_RADIUS_METERS * Math.toRadians(latitude - originLatitude);
        }

        double latitude(double y) {
            return originLatitude + Math.toDegrees(y / EARTH_RADIUS_METERS);
        }

        double longitude(double x) {
            double metersPerRadian = EARTH_RADIUS_METERS * Math.cos(originLatitudeRadians);
            if (Math.abs(metersPerRadian) < 1d) {
                return originLongitude;
            }
            return originLongitude + Math.toDegrees(x / metersPerRadian);
        }
    }

    private static final class MeterSample {
        final double x;
        final double y;
        final long timeMillis;

        MeterSample(double x, double y, long timeMillis) {
            this.x = x;
            this.y = y;
            this.timeMillis = timeMillis;
        }
    }

    private static final class AxisKalmanFilter {
        double position;
        double velocity;
        private double p00;
        private double p01;
        private double p10;
        private double p11;

        AxisKalmanFilter(double position, double positionVariance, double velocityVariance) {
            this.position = position;
            this.velocity = 0d;
            this.p00 = positionVariance;
            this.p01 = 0d;
            this.p10 = 0d;
            this.p11 = velocityVariance;
        }

        void predict(double dtSeconds) {
            if (dtSeconds <= 0d) {
                return;
            }

            position += velocity * dtSeconds;

            double oldP00 = p00;
            double oldP01 = p01;
            double oldP10 = p10;
            double oldP11 = p11;
            double accelerationVariance = ACCELERATION_NOISE_METERS_PER_SECOND2
                    * ACCELERATION_NOISE_METERS_PER_SECOND2;
            double dt2 = dtSeconds * dtSeconds;
            double dt3 = dt2 * dtSeconds;
            double dt4 = dt2 * dt2;

            p00 = oldP00
                    + dtSeconds * (oldP10 + oldP01)
                    + dt2 * oldP11
                    + accelerationVariance * dt4 / 4d;
            p01 = oldP01 + dtSeconds * oldP11 + accelerationVariance * dt3 / 2d;
            p10 = oldP10 + dtSeconds * oldP11 + accelerationVariance * dt3 / 2d;
            p11 = oldP11 + accelerationVariance * dt2;
        }

        void update(double measurement, double measurementVariance) {
            double innovation = measurement - position;
            double innovationVariance = p00 + measurementVariance;
            if (innovationVariance <= 0d) {
                return;
            }

            double k0 = p00 / innovationVariance;
            double k1 = p10 / innovationVariance;
            double oldP00 = p00;
            double oldP01 = p01;

            position += k0 * innovation;
            velocity += k1 * innovation;

            p00 -= k0 * oldP00;
            p01 -= k0 * oldP01;
            p10 -= k1 * oldP00;
            p11 -= k1 * oldP01;
        }
    }
}
