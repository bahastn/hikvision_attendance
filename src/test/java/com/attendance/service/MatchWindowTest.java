package com.attendance.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MatchWindowTest {

    private static final LocalDateTime CAMERA_NOW = LocalDateTime.of(2026, 11, 27, 9, 0, 0);

    @Test
    void firstRunLooksBackAndReachesSlightlyIntoTheFuture() {
        MatchWindow.Window w = MatchWindow.window(null, CAMERA_NOW, 120, 60, 72);
        assertEquals(CAMERA_NOW.minusMinutes(60), w.from());
        assertEquals(CAMERA_NOW.plusSeconds(60), w.to());
        assertFalse(w.cursorReset());
    }

    @Test
    void normalPollReReadsTheOverlap() {
        LocalDateTime cursor = CAMERA_NOW.minusSeconds(5);
        MatchWindow.Window w = MatchWindow.window(cursor, CAMERA_NOW, 120, 60, 72);
        assertEquals(cursor.minusSeconds(120), w.from());
    }

    @Test
    void cursorInTheCamerasFutureIsReset() {
        // Old logic saved server time; the camera is 3 hours behind (time zone not set).
        LocalDateTime serverClockCursor = CAMERA_NOW.plusHours(3);
        MatchWindow.Window w = MatchWindow.window(serverClockCursor, CAMERA_NOW, 120, 60, 72);
        assertTrue(w.cursorReset());
        assertTrue(!w.from().isAfter(CAMERA_NOW.minusSeconds(120)), "from " + w.from());
    }

    @Test
    void windowAlwaysCoversTheRecentPastEvenWithAFreshCursor() {
        LocalDateTime cursor = CAMERA_NOW.plusSeconds(30); // slightly ahead but within tolerance
        MatchWindow.Window w = MatchWindow.window(cursor, CAMERA_NOW, 120, 60, 72);
        assertFalse(w.cursorReset());
        assertEquals(CAMERA_NOW.minusSeconds(120), w.from());
    }

    @Test
    void catchUpIsBounded() {
        MatchWindow.Window w = MatchWindow.window(CAMERA_NOW.minusDays(30), CAMERA_NOW, 120, 60, 72);
        assertEquals(CAMERA_NOW.minusHours(72), w.from());
    }

    @Test
    void nextCursorFollowsNewestMatchButStopsAtFailures() {
        LocalDateTime newest = CAMERA_NOW.minusSeconds(10);
        // Idle: camera now - overlap.
        assertEquals(CAMERA_NOW.minusSeconds(120), MatchWindow.nextCursor(CAMERA_NOW, 120, null, null));
        // Newest match is later than now - overlap.
        assertEquals(newest, MatchWindow.nextCursor(CAMERA_NOW, 120, newest, null));
        // A failed match holds the cursor so it is fetched again.
        LocalDateTime failed = CAMERA_NOW.minusMinutes(10);
        assertEquals(failed, MatchWindow.nextCursor(CAMERA_NOW, 120, newest, failed));
    }

    @Test
    void cameraBehindServerNoLongerHidesMatches() {
        // Camera clock is 40 s behind the server. A match stamped 08:59:50 camera time must be
        // inside the next window and not before the cursor.
        LocalDateTime match = CAMERA_NOW.minusSeconds(10);
        LocalDateTime cursor = MatchWindow.nextCursor(CAMERA_NOW.minusSeconds(20), 120, null, null);
        MatchWindow.Window w = MatchWindow.window(cursor, CAMERA_NOW, 120, 60, 72);
        assertTrue(!match.isBefore(w.from()) && !match.isAfter(w.to()));
    }
}
