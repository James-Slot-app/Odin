package io.hiddemoens.pluskey;

import android.os.FileObserver;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Share one inotify watch per directory, including while a foreground request is awaiting a reply. */
final class MailboxEvents extends FileObserver {
    interface Listener { void changed(int event, String path); }
    interface Subscription extends AutoCloseable { @Override void close(); }
    private static final Map<File, MailboxEvents> watches = new HashMap<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private MailboxEvents(File dir) { super(dir, MOVED_TO | CLOSE_WRITE); }

    static synchronized Subscription watch(File dir, Listener listener) {
        MailboxEvents events = watches.get(dir);
        if (events == null) {
            events = new MailboxEvents(dir);
            events.startWatching();
            watches.put(dir, events);
        }
        events.listeners.add(listener);
        return () -> remove(dir, listener);
    }

    private static synchronized void remove(File dir, Listener listener) {
        MailboxEvents events = watches.get(dir);
        if (events == null || !events.listeners.remove(listener)) return;
        if (events.listeners.isEmpty()) {
            events.stopWatching();
            watches.remove(dir);
        }
    }

    @Override public void onEvent(int event, String path) {
        synchronized (MailboxEvents.class) {
            for (Listener listener : listeners) listener.changed(event, path);
        }
    }

    /** Arm before sending the request. Ignore cached files until an atomic replacement arrives. */
    static final class Reply implements AutoCloseable {
        private final Semaphore changed = new Semaphore(0);
        private final Subscription subscription;

        Reply(File dir, String file) {
            subscription = watch(dir, (event, path) -> {
                if (event == MOVED_TO && file.equals(path)) changed.release();
            });
        }

        interface Read { String value() throws IOException; }

        String await(long timeoutMs, Read read, Predicate<String> matches) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (changed.tryAcquire(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                changed.drainPermits();
                String value = read.value();
                if (value != null && matches.test(value)) return value;
                if (System.nanoTime() >= deadline) break;
            }
            return null;
        }

        private String awaitStatus(Mailbox box, long timeoutMs, Predicate<String> matches)
                throws IOException, InterruptedException {
            return await(timeoutMs, () -> box.readStatus(System.currentTimeMillis()), matches);
        }

        void awaitStopped(Mailbox box) throws IOException, InterruptedException {
            if (awaitStatus(box, 3500, value -> value.startsWith("Stopped|")) == null)
                throw new IOException("Previous helper did not stop");
        }

        void awaitReady(Mailbox box) throws IOException, InterruptedException {
            String status = awaitStatus(box, 5000, value -> value.startsWith("Listening|") || value.startsWith("Error|"));
            if (status == null) throw new IOException("Helper did not become ready");
            if (status.startsWith("Error|")) throw new IOException(status.substring(status.lastIndexOf('|') + 1));
        }

        @Override public void close() { subscription.close(); }
    }
}
