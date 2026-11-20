package io.hiddemoens.pluskey;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
final class KeyPress {
    static final int SHORT = 1, LONG = 2, DOUBLE = 3, TRIPLE = 4;
    private static final Pattern EVENT = Pattern.compile("\\[\\s*([0-9]+(?:\\.[0-9]+)?)\\]\\s+(?:/dev/input/event[0-9]+:\\s+)?EV_KEY\\s+BTN_TRIGGER_HAPPY32\\s+(DOWN|UP|REPEAT)\\s*");
    private static final long HOLD_MS = 500;
    private final ScheduledExecutorService timer;
    private final IntConsumer dispatch;
    private final Supplier<Set<Integer>> activeGestureIds;
    private final IntSupplier interval;
    private final Runnable release;
    private final Consumer<Character> captureSymbol;
    private final BooleanSupplier capturing;
    private final Set<String> patterns = new HashSet<>();
    private String pending = "";
    private ScheduledFuture<?> pendingHold;
    private ScheduledFuture<?> pendingClicks;
    private long pressId;
    private long clickTimerId;
    private int clickIntervalMs;
    private long lastReleaseUs;
    private long eventDownUs = -1;
    private boolean holdRecognized;
    private boolean usesHolds;
    private boolean releaseDue;
    private boolean capturePress;

    KeyPress() {
        this(null, null, () -> 0);
    }

    KeyPress(ScheduledExecutorService timer, IntConsumer dispatch, IntSupplier activeClickInterval) {
        this(timer, dispatch, null, activeClickInterval, () -> {}, symbol -> {}, () -> false);
    }

    KeyPress(ScheduledExecutorService timer, IntConsumer dispatch, Supplier<Set<Integer>> activeGestureIds,
             IntSupplier interval, Runnable release, Consumer<Character> captureSymbol, BooleanSupplier capturing) {
        this.timer = timer;
        this.dispatch = dispatch;
        this.activeGestureIds = activeGestureIds;
        this.interval = interval;
        this.release = release;
        this.captureSymbol = captureSymbol;
        this.capturing = capturing;
    }

    boolean accept(String line) { return gesture(line) == SHORT; }

    int gesture(String line) {
        Matcher match = EVENT.matcher(line);
        if (!match.matches()) return 0;
        String value = match.group(2);
        long eventUs = Math.round(Double.parseDouble(match.group(1)) * 1_000_000);
        return gesture(eventUs, value.equals("DOWN") ? 1 : value.equals("UP") ? 0 : 2);
    }

    synchronized int gesture(long eventUs, int value) {
        if (value == 1 && eventDownUs < 0) {
            capturePress = capturing.getAsBoolean();
            cancelClickTimer();
            long gapUs = eventUs - lastReleaseUs;
            if (capturePress) pending = "";
            else if (gapUs < 0 || gapUs >= clickIntervalMs * 1000L) resolve(false, false, false);
            eventDownUs = eventUs;
            holdRecognized = false;
            if (!capturePress && pending.isEmpty()) loadPatterns();
            if (timer != null && (capturePress || usesHolds)) {
                long id = ++pressId;
                pendingHold = timer.schedule(() -> fireHold(id), HOLD_MS, TimeUnit.MILLISECONDS);
            }
        }
        if (value != 0 || eventDownUs < 0) return 0;
        if (pendingHold != null) {
            pendingHold.cancel(false);
            pendingHold = null;
        }
        long durationUs = eventUs - eventDownUs;
        int result = 0;
        boolean recording = capturing.getAsBoolean();
        if (recording) { capturePress = true; pending = ""; }
        if (!holdRecognized && durationUs >= 0) result = symbol(durationUs >= HOLD_MS * 1000 ? '-' : '.', true, recording);
        else if (durationUs < 0) resolve(false, false, false);
        eventDownUs = -1;
        if (releaseDue) {
            releaseDue = false;
            release.run();
        }
        if (!pending.isEmpty()) {
            lastReleaseUs = eventUs;
            long id = ++clickTimerId;
            pendingClicks = timer.schedule(() -> fireClicks(id), clickIntervalMs, TimeUnit.MILLISECONDS);
        }
        return result;
    }

    private synchronized void fireHold(long id) {
        if (id != pressId || eventDownUs < 0 || holdRecognized) return;
        pendingHold = null;
        holdRecognized = true;
        symbol('-', false, capturing.getAsBoolean());
    }

    private synchronized void fireClicks(long id) {
        if (id != clickTimerId) return;
        cancelClickTimer();
        if (capturing.getAsBoolean()) pending = "";
        else resolve(false, false, false);
    }

    private int symbol(char symbol, boolean onUp, boolean recording) {
        if (recording || capturePress) {
            capturePress = true;
            pending = "";
            if (recording) captureSymbol.accept(symbol);
            return 0;
        }
        pending += symbol;
        return resolve(clickIntervalMs > 0 && timer != null, true, onUp && activeGestureIds == null);
    }

    private void loadPatterns() {
        patterns.clear();
        if (activeGestureIds == null) {
            patterns.add("."); patterns.add("-"); patterns.add(".."); patterns.add("...");
        } else {
            for (int id : activeGestureIds.get()) patterns.add(GesturePatterns.text(id));
        }
        usesHolds = false;
        boolean multiPress = false;
        for (String pattern : patterns) {
            usesHolds |= pattern.indexOf('-') >= 0;
            multiPress |= pattern.length() > 1;
        }
        clickIntervalMs = multiPress ? interval.getAsInt() : 0;
    }

    private boolean hasExtension(String prefix) {
        for (String pattern : patterns)
            if (pattern.length() > prefix.length() && pattern.startsWith(prefix)) return true;
        return false;
    }

    private int resolve(boolean allowWait, boolean currentPress, boolean returnFinal) {
        while (!pending.isEmpty()) {
            if (allowWait && hasExtension(pending)) return 0;
            String longest = "";
            for (String pattern : patterns) {
                if (pattern.length() > longest.length() && pending.startsWith(pattern)) longest = pattern;
            }
            pending = pending.substring(Math.max(1, longest.length()));
            if (longest.isEmpty()) continue;
            int gesture = GesturePatterns.id(longest);
            if (pending.isEmpty() && returnFinal) return gesture;
            if (longest.endsWith("-")) {
                if (pending.isEmpty() && currentPress) releaseDue = true;
                else if (!hasExtension(longest)) continue;
            }
            dispatch.accept(gesture);
        }
        return 0;
    }

    synchronized void reset() {
        ++pressId;
        if (pendingHold != null) pendingHold.cancel(false);
        pendingHold = null;
        cancelClickTimer();
        eventDownUs = -1;
        pending = "";
        releaseDue = false;
        capturePress = false;
    }

    private void cancelClickTimer() {
        clickTimerId++;
        if (pendingClicks != null) {
            pendingClicks.cancel(false);
            pendingClicks = null;
        }
    }
    static String device(String text) {
        String path = null;
        for (String line : text.split("\\n")) {
            if (line.startsWith("add device ")) {
                String candidate = line.substring(line.indexOf(":") + 1).trim();
                path = candidate.matches("/dev/input/event[0-9]+") ? candidate : null;
            }
            if (line.contains("BTN_TRIGGER_HAPPY32") && path != null) return path;
        }
        return null;
    }
}
