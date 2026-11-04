package ai.devvio.rd;

/**
 * Turns Android IME/EditText edits into remote keyboard events.
 *
 * <p>Problem this solves: a soft keyboard does not emit one key event per
 * character - it emits *committed text*, often after composition (Persian
 * ی‌, Arabic, CJK, emoji predictive input). Sending raw key codes therefore
 * loses text, and non-ASCII characters have no key code at all.
 *
 * <p>Approach: mirror the text field into a lightweight gap buffer
 * ({@link Mirror}) and diff it against the edit reported by the TextWatcher.
 * Because Android tells us exactly what changed (start/before/count) the
 * common-case cost is proportional to the edit, not to the field length - a
 * text input stays responsive even with thousands of characters and heavy
 * Persian input. Deletions become Backspace events (the only portable way to
 * delete remotely) and insertions are sent as UTF-8 text events.
 *
 * <p>Pure logic with no Android imports, so it is unit tested on the JVM.
 */
public final class ImeBridge {

    /** Sink for generated remote input. */
    public interface Sink {
        void backspace();

        void text(String s);
    }

    private final Sink sink;
    private final Mirror mirror = new Mirror();
    private boolean suppress;
    private StringBuilder scratch = new StringBuilder();

    public ImeBridge(Sink sink) {
        this.sink = sink;
    }

    /** Text currently mirrored to the remote host. */
    public String sentText() {
        return mirror.toString();
    }

    /**
     * Resets the mirror without emitting anything. Call this when the field is
     * cleared programmatically (e.g. opening the keyboard) so the clear is not
     * mistaken for a user deletion.
     */
    public void resetField() {
        suppress = true;
        mirror.clear();
    }

    /**
     * Feeds the full new field value (no edit metadata available). This is the
     * diff path: it is also the safety net when an IME reports an edit that
     * does not match the mirror.
     */
    public void onTextChanged(String current) {
        String value = current == null ? "" : current;
        if (suppress) {
            suppress = false;
            mirror.clear();
            if (value.isEmpty()) {
                return;
            }
            mirror.append(value);
            sink.text(value);
            return;
        }
        diff(value);
    }

    /**
     * Feeds an incremental edit: {@code count} characters were inserted at
     * {@code start}, replacing {@code before} characters.
     *
     * @param full   the complete new field content
     * @param start  index of the edit
     * @param before number of characters removed at {@code start}
     * @param count  number of characters inserted at {@code start}
     */
    public void onTextChanged(String full, int start, int before, int count) {
        if (full == null) {
            full = "";
        }
        if (suppress) {
            suppress = false;
            mirror.clear();
            if (full.isEmpty()) {
                return;
            }
            mirror.append(full);
            sink.text(full);
            return;
        }
        if (before == 0 && count == 0) {
            // No usable edit info: fall back to a full diff.
            diff(full);
            return;
        }
        if (start < 0 || start > mirror.length() || before < 0 || count < 0
                || start + before > mirror.length() || start + count > full.length()) {
            diff(full);
            return;
        }
        // Delete the removed region.
        for (int i = 0; i < before; i++) {
            sink.backspace();
        }
        mirror.delete(start, before);
        if (count > 0) {
            String added = full.substring(start, start + count);
            mirror.insert(start, added);
            sink.text(added);
        }
        // Self-check: if the mirror still disagrees (exotic IME behaviour),
        // resynchronise on the next edit rather than drifting silently.
        if (mirror.length() != full.length() || !mirror.endsWith(full)) {
            diff(full);
        }
    }

    /** Mirror of the remote text: O(1) appends, O(n) inserts, cheap length. */
    static final class Mirror {
        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();

        int length() {
            return head.length() + tail.length();
        }

        void clear() {
            head.setLength(0);
            tail.setLength(0);
        }

        void append(String s) {
            // Rebalance so appends stay O(1) amortised.
            head.append(s);
            if (tail.length() > 0) {
                head.append(tail);
                tail.setLength(0);
            }
        }

        void insert(int index, String s) {
            materialize();
            head.insert(index, s);
        }

        void delete(int index, int count) {
            materialize();
            head.delete(index, index + count);
        }

        private void materialize() {
            if (tail.length() > 0) {
                head.append(tail);
                tail.setLength(0);
            }
        }

        boolean endsWith(String s) {
            materialize();
            if (s.length() > head.length()) {
                return false;
            }
            return head.indexOf(s, head.length() - s.length()) == head.length() - s.length();
        }

        @Override
        public String toString() {
            materialize();
            return head.toString();
        }
    }

    /** Full-diff fallback (also used when the field is replaced wholesale). */
    private void diff(String current) {
        String sent = mirror.toString();
        if (current.equals(sent)) {
            return;
        }
        if (current.isEmpty()) {
            int n = sent.codePointCount(0, sent.length());
            for (int i = 0; i < n; i++) {
                sink.backspace();
            }
            mirror.clear();
            return;
        }
        int common = commonPrefix(sent, current);
        String deleted = sent.substring(common);
        String added = current.substring(common);
        int delCount = deleted.codePointCount(0, deleted.length());
        for (int i = 0; i < delCount; i++) {
            sink.backspace();
        }
        if (!added.isEmpty()) {
            sink.text(added);
        }
        mirror.clear();
        mirror.append(current);
    }

    /**
     * Longest common prefix that does not split a surrogate pair, so emoji and
     * other supplementary characters are never cut in half.
     */
    static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1)) && i < a.length()
                && Character.isLowSurrogate(a.charAt(i))) {
            i--;
        }
        return i;
    }

    /** Marks the mirror as current without emitting anything. */
    public void syncSilently(String value) {
        mirror.clear();
        if (value != null && !value.isEmpty()) {
            mirror.append(value);
        }
    }
}
