package com.paddleshock.replay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link ReplayRecorder}'s ring-buffer capping/overwrite behavior in isolation - pure
 * logic, no jME graphics needed (mirrors the headless-but-real style of
 * {@code MatchSimulationTest}, minus the need for any jME entities at all since this class has
 * no jME dependency).
 */
class ReplayRecorderTest {

    private ReplayRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ReplayRecorder();
    }

    private ReplaySample sampleWithTpf(float tpf, int tag) {
        return new ReplaySample(tpf, tag, 0, 0, 0, 0, 0, 0, 0, 0, 0, tag, tag);
    }

    @Test
    void freshRecorderIsEmpty() {
        assertEquals(0, recorder.size());
        assertEquals(0f, recorder.totalRecordedSeconds());
        assertTrue(recorder.snapshot().isEmpty());
    }

    @Test
    void recordingBelowTheCapKeepsEverySample() {
        for (int i = 0; i < 10; i++) {
            recorder.record(sampleWithTpf(0.1f, i));
        }
        assertEquals(10, recorder.size());
        assertEquals(1.0f, recorder.totalRecordedSeconds(), 1e-5f);

        List<ReplaySample> snapshot = recorder.snapshot();
        assertEquals(10, snapshot.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, snapshot.get(i).playerScore());
        }
    }

    @Test
    void recordingPastTheCapDropsOldestSamplesFirst() {
        // 100 ticks * 0.1s = 10s of ticks recorded, but the cap is 8s - only the newest ~80
        // samples (tags 20..99) should remain, oldest-to-newest order preserved.
        int totalTicks = 100;
        for (int i = 0; i < totalTicks; i++) {
            recorder.record(sampleWithTpf(0.1f, i));
        }

        assertTrue(recorder.totalRecordedSeconds() <= ReplayRecorder.REPLAY_WINDOW_SECONDS + 1e-4f,
                "buffer must never hold more than the ~8s cap");
        assertTrue(recorder.size() < totalTicks, "older samples must have been dropped");

        List<ReplaySample> snapshot = recorder.snapshot();
        // Oldest surviving sample's tag tells us how many were dropped from the front.
        int oldestSurvivingTag = snapshot.get(0).playerScore();
        assertTrue(oldestSurvivingTag > 0, "the very first sample should have been evicted");
        // Newest sample is always the most recently recorded one.
        assertEquals(totalTicks - 1, snapshot.get(snapshot.size() - 1).playerScore());
        // Contiguous and strictly increasing - nothing dropped from the middle, only the front.
        for (int i = 1; i < snapshot.size(); i++) {
            assertEquals(snapshot.get(i - 1).playerScore() + 1, snapshot.get(i).playerScore());
        }
    }

    @Test
    void bufferedSecondsNeverExceedsCapEvenWithHugeIndividualSamples() {
        // A single sample longer than the whole window must still be kept - the "keep at least
        // one sample" guarantee - even though it alone exceeds the nominal cap.
        recorder.record(sampleWithTpf(20f, 1));
        recorder.record(sampleWithTpf(0.1f, 2));

        List<ReplaySample> snapshot = recorder.snapshot();
        assertEquals(1, snapshot.size());
        assertEquals(2, snapshot.get(0).playerScore());
    }

    @Test
    void resetClearsTheBuffer() {
        for (int i = 0; i < 5; i++) {
            recorder.record(sampleWithTpf(0.1f, i));
        }
        recorder.reset();

        assertEquals(0, recorder.size());
        assertEquals(0f, recorder.totalRecordedSeconds());
        assertTrue(recorder.snapshot().isEmpty());
    }

    @Test
    void memoryStaysBoundedAcrossAnArbitrarilyLongMatch() {
        // Simulate a very long match (equivalent of many minutes of 60fps ticks) and confirm the
        // buffer never grows past what ~8s of ticks at this rate requires - the whole point of a
        // ring buffer instead of an ever-growing list.
        float tpf = 1f / 60f;
        int oneSecondOfTicks = 60;
        for (int i = 0; i < oneSecondOfTicks * 600; i++) { // 10 minutes of ticks
            recorder.record(sampleWithTpf(tpf, i));
        }
        int expectedApproxSize = (int) (ReplayRecorder.REPLAY_WINDOW_SECONDS / tpf) + 1;
        assertTrue(recorder.size() <= expectedApproxSize,
                "buffer size (" + recorder.size() + ") must stay bounded near " + expectedApproxSize);
    }
}
