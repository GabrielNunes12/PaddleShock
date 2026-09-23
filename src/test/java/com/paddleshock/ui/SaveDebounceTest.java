package com.paddleshock.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SaveDebounceTest {

    @Test
    void doesNothingUntilAChangeHappens() {
        SaveDebounce debounce = new SaveDebounce();
        assertFalse(debounce.isDirty());
        assertFalse(debounce.update(10f));
        assertFalse(debounce.flush());
    }

    @Test
    void firesOnceAfterTheDelayHasElapsedSinceTheLastChange() {
        SaveDebounce debounce = new SaveDebounce();
        debounce.markChanged();

        assertTrue(debounce.isDirty());
        assertFalse(debounce.update(0.2f));
        assertFalse(debounce.update(0.2f));
        // Total elapsed is now 0.4s, still under the 0.5s delay.
        assertTrue(debounce.isDirty());

        assertTrue(debounce.update(0.2f), "0.6s of quiet since the last change should have fired");
        assertFalse(debounce.isDirty());
        // Doesn't fire again on subsequent frames with nothing new to save.
        assertFalse(debounce.update(1f));
    }

    @Test
    void repeatedChangesKeepResettingTheTimer() {
        // Simulates a slider being dragged: a change every frame should never fire a save while
        // the drag continues, only once it stops.
        SaveDebounce debounce = new SaveDebounce();
        for (int i = 0; i < 20; i++) {
            debounce.markChanged();
            assertFalse(debounce.update(0.1f), "still dragging at step " + i + ", should not have saved yet");
        }
        // Dragging stopped; the delay must elapse from the LAST change, not the first.
        assertFalse(debounce.update(0.3f));
        assertTrue(debounce.update(0.3f));
    }

    @Test
    void flushForcesAPendingSaveImmediately() {
        SaveDebounce debounce = new SaveDebounce();
        debounce.markChanged();
        assertTrue(debounce.flush());
        assertFalse(debounce.isDirty());
        // Nothing left to flush a second time.
        assertFalse(debounce.flush());
    }

    @Test
    void updateAfterExactlyTheDelayFires() {
        SaveDebounce debounce = new SaveDebounce();
        debounce.markChanged();
        assertTrue(debounce.update(SaveDebounce.DELAY_SECONDS));
    }
}
