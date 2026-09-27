package com.krypton.afkdiagnose;

import java.time.Duration;

/** Echte verstrichene Zeit, nicht FPS oder Anzahl der Client-Ticks. */
final class ObservationClock {
    static final long INTERVAL = Duration.ofMinutes(10).toNanos();
    static final long SETTLE = Duration.ofSeconds(10).toNanos();
    static final long GAP = Duration.ofSeconds(30).toNanos();
    private long previousTick;
    private long nextSample;

    ObservationClock(long now) {
        previousTick = now;
        nextSample = now + SETTLE;
    }

    long tick(long now) {
        long elapsed = now - previousTick;
        previousTick = now;
        return elapsed >= GAP ? elapsed : 0;
    }

    boolean due(long now) { return now - nextSample >= 0; }

    void requestAfterWorldChange(long now) {
        // Die Client-Chunks duerfen erst eintreffen. Eine bereits faellige
        // Messung bleibt aber faellig und geht durch Transfers nicht verloren.
        if (nextSample - now > SETTLE) nextSample = now + SETTLE;
    }

    void completed(long now) { nextSample = now + INTERVAL; }
}
