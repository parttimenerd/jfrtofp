package me.bechberger.jfrtofp.converter;

/**
 * Minimal JSON writer that builds the output incrementally into a single
 * StringBuilder. Designed for the WASM hot path: every operation is allocation-free
 * apart from the underlying buffer growth.
 *
 * <p>The writer does not validate structure (e.g. it will not detect a key without
 * a matching value); the caller is expected to drive it correctly.
 */
public final class JsonWriter {
    private final StringBuilder out;
    // Each entry tracks whether the current container has emitted at least one element,
    // so the writer knows whether to insert a leading comma before the next one.
    private boolean[] needsComma = new boolean[16];
    private int depth = 0;

    public JsonWriter(int initialCapacity) {
        this.out = new StringBuilder(initialCapacity);
    }

    public StringBuilder buffer() {
        return out;
    }

    public String toJson() {
        return out.toString();
    }

    public int length() {
        return out.length();
    }

    /** Truncate to {@code newLength}; useful for scratch-writer reuse. */
    public JsonWriter truncate(int newLength) {
        out.setLength(newLength);
        depth = 0;
        return this;
    }

    /** Read the buffer slice [from, to) without copying. */
    public String substring(int from, int to) {
        return out.substring(from, to);
    }

    // ── Containers ────────────────────────────────────────────────────────────

    public JsonWriter beginObject() {
        prefix();
        out.append('{');
        push();
        return this;
    }

    public JsonWriter endObject() {
        pop();
        out.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        prefix();
        out.append('[');
        push();
        return this;
    }

    public JsonWriter endArray() {
        pop();
        out.append(']');
        return this;
    }

    /** Begin a value inside the current array. */
    public JsonWriter beginArrayElement() {
        prefix();
        return this;
    }

    /** Begin a key/value pair inside the current object. */
    public JsonWriter key(String name) {
        prefix();
        appendString(name);
        out.append(':');
        // The value that follows should NOT consume a comma — neutralise the
        // pending comma flag so the next prefix() doesn't emit one.
        if (depth > 0) needsComma[depth - 1] = false;
        return this;
    }

    // ── Primitives ────────────────────────────────────────────────────────────

    public JsonWriter value(String s) {
        prefix();
        if (s == null) out.append("null");
        else appendString(s);
        return this;
    }

    public JsonWriter valueOrNull(String s) {
        return value(s);
    }

    public JsonWriter value(long v) {
        prefix();
        out.append(v);
        return this;
    }

    public JsonWriter value(int v) {
        prefix();
        out.append(v);
        return this;
    }

    public JsonWriter value(double v) {
        prefix();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            out.append("null");
        } else if (v == (long) v && Math.abs(v) < 1e16) {
            // Integer-valued doubles serialize without a fractional part to match V8.
            out.append((long) v);
        } else {
            out.append(v);
        }
        return this;
    }

    public JsonWriter value(boolean v) {
        prefix();
        out.append(v ? "true" : "false");
        return this;
    }

    public JsonWriter nullValue() {
        prefix();
        out.append("null");
        return this;
    }

    /** Raw JSON inserted verbatim (no escaping). */
    public JsonWriter raw(String json) {
        prefix();
        out.append(json);
        return this;
    }

    // ── Convenience ──────────────────────────────────────────────────────────

    public JsonWriter keyString(String key, String value) {
        return key(key).value(value);
    }

    public JsonWriter keyLong(String key, long value) {
        return key(key).value(value);
    }

    public JsonWriter keyInt(String key, int value) {
        return key(key).value(value);
    }

    public JsonWriter keyDouble(String key, double value) {
        return key(key).value(value);
    }

    public JsonWriter keyBoolean(String key, boolean value) {
        return key(key).value(value);
    }

    public JsonWriter keyNull(String key) {
        return key(key).nullValue();
    }

    /** Emit a JSON-encoded array of longs. */
    public JsonWriter longArray(long[] values, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        out.append(']');
        return this;
    }

    public JsonWriter intArray(int[] values, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        out.append(']');
        return this;
    }

    public JsonWriter doubleArray(double[] values, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            double v = values[i];
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                out.append("null");
            } else if (v == (long) v && Math.abs(v) < 1e16) {
                out.append((long) v);
            } else {
                out.append(v);
            }
        }
        out.append(']');
        return this;
    }

    /** Emit a JSON-encoded array of nullable ints (uses null when sentinel matches). */
    public JsonWriter intArrayNullable(int[] values, int length, int nullSentinel) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            int v = values[i];
            if (v == nullSentinel) out.append("null");
            else out.append(v);
        }
        out.append(']');
        return this;
    }

    public JsonWriter stringArray(String[] values, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            String s = values[i];
            if (s == null) out.append("null");
            else appendString(s);
        }
        out.append(']');
        return this;
    }

    public JsonWriter boolArray(boolean[] values, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i] ? "true" : "false");
        }
        out.append(']');
        return this;
    }

    /** Emit `length` repetitions of the same value (e.g. column of nulls). */
    public JsonWriter repeatedNull(int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append("null");
        }
        out.append(']');
        return this;
    }

    public JsonWriter repeatedInt(int value, int length) {
        prefix();
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(',');
            out.append(value);
        }
        out.append(']');
        return this;
    }

    public JsonWriter repeatedZero(int length) {
        return repeatedInt(0, length);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void prefix() {
        if (depth == 0) return;
        if (needsComma[depth - 1]) {
            out.append(',');
        } else {
            needsComma[depth - 1] = true;
        }
    }

    private void push() {
        if (depth >= needsComma.length) {
            boolean[] grown = new boolean[needsComma.length * 2];
            System.arraycopy(needsComma, 0, grown, 0, needsComma.length);
            needsComma = grown;
        }
        needsComma[depth] = false;
        depth++;
    }

    private void pop() {
        depth--;
    }

    private void appendString(String s) {
        out.append('"');
        int len = s.length();
        // Fast path: scan for any char needing escape. If none, append the whole
        // string in a single call. Most JSON keys + many values are clean ASCII
        // identifiers, so this avoids per-char append/switch overhead.
        int i = 0;
        for (; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') break;
        }
        if (i == len) {
            out.append(s);
            out.append('"');
            return;
        }
        if (i > 0) out.append(s, 0, i);
        for (; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00");
                        out.append(HEX[(c >> 4) & 0xF]);
                        out.append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final char[] HEX = {
        '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'
    };
}
