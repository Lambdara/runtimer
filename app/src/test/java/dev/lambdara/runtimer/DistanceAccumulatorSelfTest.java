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

        DistanceAccumulator.AddResult start = accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        DistanceAccumulator.AddResult first = accumulator.add(52.000450, 5.000000, 10_000L, true, 5f);
        DistanceAccumulator.AddResult second = accumulator.add(52.000900, 5.000000, 20_000L, true, 5f);

        assertRecorded(start, false, "starting point");
        assertClose(50d, first.addedMeters, 1.0d, "first normal segment");
        assertRecorded(first, true, "first normal segment");
        assertClose(50d, second.addedMeters, 1.0d, "second normal segment");
        assertRecorded(second, true, "second normal segment");
    }

    private static void ignoresInaccurateLocationsWithoutMovingAnchorPoint() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        DistanceAccumulator.AddResult inaccurate = accumulator.add(52.010000, 5.000000, 10_000L, true, 120f);
        DistanceAccumulator.AddResult accurate = accumulator.add(52.000450, 5.000000, 20_000L, true, 5f);

        assertClose(0d, inaccurate.addedMeters, 0.01d, "inaccurate point");
        if (inaccurate.recorded) {
            throw new AssertionError("inaccurate point should not be recorded");
        }
        assertClose(50d, accurate.addedMeters, 1.0d, "accurate point after inaccurate point");
        assertRecorded(accurate, true, "accurate point after inaccurate point");
    }

    private static void rejectsImplausibleSpeedButKeepsNextAnchorPoint() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        DistanceAccumulator.AddResult spike = accumulator.add(52.009000, 5.000000, 10_000L, true, 5f);
        DistanceAccumulator.AddResult afterSpike = accumulator.add(52.009450, 5.000000, 20_000L, true, 5f);

        assertClose(0d, spike.addedMeters, 0.01d, "implausible speed point");
        assertRecorded(spike, false, "implausible speed point");
        assertClose(50d, afterSpike.addedMeters, 1.0d, "point after implausible speed point");
        assertRecorded(afterSpike, true, "point after implausible speed point");
    }

    private static void resetStartsANewPhaseWithoutCarryOverDistance() {
        DistanceAccumulator accumulator = new DistanceAccumulator(65f, 14d);

        accumulator.add(52.000000, 5.000000, 0L, true, 5f);
        accumulator.add(52.000450, 5.000000, 10_000L, true, 5f);
        accumulator.reset();
        DistanceAccumulator.AddResult firstPointOfNextPhase = accumulator.add(52.000900, 5.000000, 20_000L, true, 5f);

        assertClose(0d, firstPointOfNextPhase.addedMeters, 0.01d, "first point after reset");
        assertRecorded(firstPointOfNextPhase, false, "first point after reset");
    }

    private static void assertClose(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + " +/- " + tolerance + ", got " + actual);
        }
    }

    private static void assertRecorded(DistanceAccumulator.AddResult result, boolean connectsFromPrevious, String label) {
        if (!result.recorded) {
            throw new AssertionError(label + ": expected point to be recorded");
        }
        if (result.connectsFromPrevious != connectsFromPrevious) {
            throw new AssertionError(label + ": expected connectsFromPrevious=" + connectsFromPrevious);
        }
    }
}
