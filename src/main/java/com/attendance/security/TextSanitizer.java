package com.attendance.security;

/**
 * Small guard against stored cross-site scripting: user-entered names and labels
 * are shown in many places that build HTML from strings, so values that contain
 * angle brackets or control characters are rejected at write time.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /**
     * Return a trimmed value, or null when blank, rejecting markup characters.
     *
     * @throws IllegalArgumentException if the value contains {@code <}, {@code >} or control characters
     */
    public static String clean(String field, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '<' || c == '>') {
                throw new IllegalArgumentException(field + " must not contain '<' or '>'");
            }
            if (c < 0x20 && c != '\t') {
                throw new IllegalArgumentException(field + " contains invalid characters");
            }
        }
        return trimmed;
    }
}
