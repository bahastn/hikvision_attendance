package com.attendance.service;

import java.time.LocalDateTime;

/**
 * Pure cursor/window arithmetic for the face-comparison poller, in <em>camera</em> time.
 * Kept free of Spring so the edge cases (camera clock behind/ahead, stale cursor, failures) are
 * unit-tested.
 */
public final class MatchWindow {

    private MatchWindow() {
    }

    /** Search window [from, to] for one poll. */
    public record Window(LocalDateTime from, LocalDateTime to, boolean cursorReset) {
    }

    /**
     * @param cursor          last saved cursor (camera time), or null on the first run
     * @param cameraNow       the camera's current time
     * @param overlapSeconds  how far each poll re-reads before the cursor
     * @param firstRunMinutes look-back when there is no cursor yet
     * @param maxCatchupHours never look further back than this
     */
    public static Window window(LocalDateTime cursor, LocalDateTime cameraNow, long overlapSeconds,
                                long firstRunMinutes, long maxCatchupHours) {
        boolean reset = false;
        if (cursor != null && cursor.isAfter(cameraNow.plusSeconds(60))) {
            // A cursor in the camera's future (saved under server-clock logic, or the camera clock
            // was changed) would hide every match until the clock caught up.
            cursor = cameraNow.minusSeconds(overlapSeconds);
            reset = true;
        }
        LocalDateTime from = cursor != null ? cursor.minusSeconds(overlapSeconds) : cameraNow.minusMinutes(firstRunMinutes);
        LocalDateTime recent = cameraNow.minusSeconds(overlapSeconds);
        if (from.isAfter(recent)) {
            from = recent;
        }
        LocalDateTime earliest = cameraNow.minusHours(maxCatchupHours);
        if (from.isBefore(earliest)) {
            from = earliest;
        }
        return new Window(from, cameraNow.plusSeconds(60), reset);
    }

    /**
     * Next cursor: the newest match seen or (idle camera) camera-now minus the overlap, whichever is
     * later — but never past the oldest failed match, so it is fetched and retried next time.
     */
    public static LocalDateTime nextCursor(LocalDateTime cameraNow, long overlapSeconds, LocalDateTime newestMatch,
                                           LocalDateTime oldestFailed) {
        LocalDateTime next = cameraNow.minusSeconds(overlapSeconds);
        if (newestMatch != null && newestMatch.isAfter(next)) {
            next = newestMatch;
        }
        if (oldestFailed != null && oldestFailed.isBefore(next)) {
            next = oldestFailed;
        }
        return next;
    }
}
