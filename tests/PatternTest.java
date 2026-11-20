package io.hiddemoens.pluskey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class PatternTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted invalid pattern or ID");
    }

    private static void encoding() {
        Set<Integer> ids = new HashSet<>();
        String[] builtins = {".", "-", "..", "..."};
        for (int index = 0; index < builtins.length; index++)
            check(GesturePatterns.id(builtins[index]) == index + 1, "legacy ID");
        for (int bits = 2; bits < 512; bits++) {
            String pattern = Integer.toBinaryString(bits).substring(1).replace('0', '.').replace('1', '-');
            int id = GesturePatterns.id(pattern);
            check(id > 0 && ids.add(id) && GesturePatterns.text(id).equals(pattern), pattern);
        }
        check("● — ●".equals(GesturePatterns.label(GesturePatterns.id(".-."))), "display label");
        for (String bad : new String[]{null, "", ".........", ". -", "●", "x"}) invalid(() -> GesturePatterns.id(bad));
        for (int bad : new int[]{0, -1, Integer.MAX_VALUE}) invalid(() -> GesturePatterns.text(bad));
    }

    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final Set<Integer> active = new HashSet<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1) {
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            tasks.add(task);
            return super.schedule(task, 1, TimeUnit.DAYS);
        }
    };
    private boolean capturing;
    private final KeyPress key = new KeyPress(timer, id -> log.add(GesturePatterns.text(id)), () -> active,
        () -> 200, () -> log.add("up"), symbol -> log.add("capture:" + symbol), () -> capturing);
    private int time = 1000;

    private void event(String value) {
        check(key.gesture("[ " + (time / 1000.0) + "] EV_KEY BTN_TRIGGER_HAPPY32 " + value) == 0,
            "new API must not also return dispatched gestures");
    }
    private Runnable last() { return tasks.get(tasks.size() - 1); }
    private void down() { time += 25; event("DOWN"); }
    private void up() { time += 25; event("UP"); }
    private void dot() { down(); up(); }
    private void hold() { down(); time += 500; last().run(); }
    private void expect(String... events) {
        check(log.equals(Arrays.asList(events)), "expected " + Arrays.toString(events) + ", got " + log);
        log.clear();
    }
    private void configure(String... patterns) {
        active.clear();
        for (String pattern : patterns) active.add(GesturePatterns.id(pattern));
    }

    private void recognition() {
        configure(".", "-");
        dot(); expect(".");
        Runnable stale = last();
        down(); stale.run(); expect(); last().run(); expect("-"); last().run(); up(); event("UP"); expect("up");
        down(); stale = last(); time += 500; up(); stale.run(); expect("-", "up");
        configure(".", "-", "..", "..-.");
        dot(); dot(); expect(); last().run(); expect("..");
        dot(); dot(); hold(); expect(); up(); dot(); expect("..-.");
        dot(); dot(); dot(); expect(".."); last().run(); expect(".");
        configure(".", "-", ".-", ".-.-");
        dot(); hold(); expect(); up(); dot(); hold(); expect(".-.-"); up(); expect("up");
        dot(); hold(); up(); last().run(); expect(".-"); event("UP"); expect();
        configure(".", "-", "..", "--------");
        dot(); dot(); expect("..");
        for (int count = 0; count < 7; count++) { hold(); up(); expect(); }
        hold(); expect("--------"); up(); expect("up");
        dot(); stale = last(); time += 200; down(); expect("."); stale.run(); up(); last().run(); expect(".");
        configure(".", "-", ".-.");
        dot(); hold(); up(); last().run(); expect(".");
        dot(); hold(); up(); hold(); expect(".", "-"); up(); expect("up");
    }

    private void captureAndReset() {
        configure(".", "-", ".-");
        dot(); capturing = true; last().run(); expect();
        dot(); hold(); last().run(); up(); event("UP"); expect("capture:.", "capture:-");
        down(); capturing = false; up(); expect();
        dot(); down(); Runnable stale = last(); key.reset(); stale.run(); up(); expect();
        dot(); stale = last(); key.reset(); dot(); stale.run(); expect(); last().run(); expect(".");
        hold(); expect("-"); capturing = true; up(); expect("up");
    }

    private void shortOnly() {
        capturing = false;
        configure(".");
        int scheduled = tasks.size();
        dot(); expect(".");
        down(); time += 600; up(); expect();
        check(tasks.size() == scheduled, "short-only configuration schedules no timers");
        capturing = true;
        hold(); up(); expect("capture:-");
        capturing = false;
    }

    public static void main(String[] args) {
        encoding();
        PatternTest test = new PatternTest();
        try { test.recognition(); test.captureAndReset(); test.shortOnly(); }
        finally { test.timer.shutdownNow(); }
        System.out.println("Pattern tests passed");
    }
}
