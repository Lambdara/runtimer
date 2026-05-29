package dev.lambdara.runtimer;

public final class DistanceAccumulatorSelfTest {
    public static void main(String[] args) {
        accumulatesNormalRunningSegments();
        ignoresInaccurateLocationsWithoutMovingAnchorPoint();
        rejectsImplausibleSpeedButKeepsNextAnchorPoint();
        resetStartsANewPhaseWithoutCarryOverDistance();
        System.out.println("Distance simulation checks passed.");
    }

    private static void accumulatesNormalRunningSegments() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        double first = accumulator.add(52.000450, 5.000000, 10_000L, true, 5f);
        double second = accumulator.add(52.000900, 5.000000, 20_000L, true, 5f);

        assertClose(50d, first, 1.0d, "first normal segment");
        assertClose(50d, second, 1.0d, "second normal segment");
    }

    private static void ignoresInaccurateLocationsWithoutMovingAnchorPoint() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        double inaccurate = accumulator.add(52.010000, 5.000000, 10_000L, true, 120f);
        double accurate = accumulator.add(52.000450, 5.000000, 20_000L, true, 5f);

        assertClose(0d, inaccurate, 0.01d, "inaccurate point");
        assertClose(50d, accurate, 1.0d, "accurate point after inaccurate point");
    }

    private static void rejectsImplausibleSpeedButKeepsNextAnchorPoint() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        double spike = accumulator.add(52.009000, 5.000000, 10_000L, true, 5f);
        double afterSpike = accumulator.add(52.009450, 5.000000, 20_000L, true, 5f);

        assertClose(0d, spike, 0.01d, "implausible speed point");
        assertClose(50d, afterSpike, 1.0d, "point after implausible speed point");
    }

    private static void resetStartsANewPhaseWithoutCarryOverDistance() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        accumulator.add(52.000450, 5.000000, 10_000L, true, 5f);
        accumulator.reset();
        double firstPointOfNextPhase = accumulator.add(52.000900, 5.000000, 20_000L, true, 5f);

        assertClose(0d, firstPointOfNextPhase, 0.01d, "first point after reset");
    }

    private static void assertClose(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + " +/- " + tolerance + ", got " + actual);
        }
    }
}
