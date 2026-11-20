package io.hiddemoens.pluskey;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.FileObserver;
import android.os.SystemClock;
import java.io.*;
import java.util.EnumSet;
import java.util.concurrent.*;

/** Runs through app_process with the identity authorized by ADB or Shizuku. */
public final class Helper {
    static final String SOCKET = "io.hiddemoens.pluskey.helper.v1";
    static final String VERSION = BuildConfig.VERSION_NAME;
    static final String ONEPLUS_ACTION_SETTING = "oplus_action_button_switch_state";
    static final String LENS = "com.google.ar.lens/com.google.vr.apps.ornament.app.lens.LensLauncherActivity";
    private static final long UNLOCK_TIMEOUT_MS = 30_000;
    private volatile boolean running = true;
    private final ScheduledThreadPoolExecutor actions = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "PlusKey-actions");
        thread.setDaemon(true);
        return thread;
    });
    private PendingUnlock pendingUnlock;
    private volatile Process readerProcess;
    private volatile String state = "Starting";
    private volatile String last = "No short press yet";
    private volatile int launches;
    private LocalServerSocket server;
    private int appUid;
    private Thread controlThread;
    static final File MAILBOX_DIR = new File("/sdcard/Android/data/io.hiddemoens.pluskey/files");
    private final Mailbox mailbox = new Mailbox(MAILBOX_DIR);
    private final PatternCapture capture = new PatternCapture(MAILBOX_DIR);
    private final java.util.concurrent.atomic.AtomicLong releases = new java.util.concurrent.atomic.AtomicLong();
    private String heldTarget;
    private enum Change { STOP, TILE_QUERY, PREVIEW, STATUS_REQUEST, STATUS_CHANGED }
    private final EnumSet<Change> changes = EnumSet.of(Change.STOP, Change.TILE_QUERY, Change.PREVIEW, Change.STATUS_REQUEST);

    private void signal(Change change) {
        synchronized (changes) {
            if (changes.add(change)) changes.notifyAll();
        }
    }

    private EnumSet<Change> awaitChanges() throws InterruptedException {
        synchronized (changes) {
            while (changes.isEmpty()) changes.wait();
            EnumSet<Change> batch = changes.clone();
            changes.clear();
            return batch;
        }
    }

    private Helper() {
        actions.setRemoveOnCancelPolicy(true);
    }

    private void enqueue(int gesture, boolean preview) {
        if (!running) return;
        long generation = releases.get();
        try {
            actions.execute(() -> {
                if (running && capture.active() == null) launch(gesture, preview, generation);
            });
        } catch (RejectedExecutionException ignored) { }
    }

    public static void main(String[] args) throws Exception {
        int uid = android.os.Process.myUid();
        boolean manual = uid == 2000 && args.length == 1;
        boolean shizuku = (uid == 0 || uid == 2000) && args.length == 2 && "shizuku".equals(args[1]);
        if (!manual && !shizuku)
            throw new SecurityException("Start helper using supplied USB script");
        Helper helper = new Helper();
        helper.appUid = Integer.parseInt(args[0]);
        if (helper.appUid < 10000) throw new SecurityException("Invalid app UID");
        // Binding first ensures a second start cannot create a duplicate reader.
        helper.server = new LocalServerSocket(SOCKET);
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        helper.controlThread = new Thread(() -> {
            try { helper.serve(uid); }
            catch (IOException error) { System.err.println("Helper stopped: " + error); }
            finally { System.exit(0); }
        }, "PlusKey-control");
        helper.controlThread.start();
        // Android services deliver their callbacks here. An idle Looper blocks in the kernel.
        android.os.Looper.loop();
    }

    private void serve(int uid) throws IOException {
        // Closing this descriptor also signals an abrupt helper exit to a visible activity.
        try (FileOutputStream session = new FileOutputStream(new File(MAILBOX_DIR, Mailbox.SESSION_FILE))) {
            mailbox.clearStop();
            mailbox.consumePreview(); // Discard any preview left by a previous helper session.
            mailbox.publishVersion(VERSION);
            mailbox.publish("Starting|0|Starting input reader", System.currentTimeMillis());
            Thread events = new Thread(this::listen, "PlusKey-input");
            events.setDaemon(true);
            events.start();
            Thread mailboxThread = new Thread(this::watchMailbox, "PlusKey-status");
            mailboxThread.setDaemon(true);
            mailboxThread.start();
            try {
                acceptClients(uid);
            } finally {
                running = false;
                mailboxThread.interrupt();
                actions.shutdownNow();
                Process reader = readerProcess;
                if (reader != null) reader.destroy();
                try {
                    endHold();
                    DeviceActions.shutdown();
                } finally {
                    server.close();
                    publishStatus("Stopped|" + launches + "|Helper stopped", true);
                }
            }
        }
    }

    private void acceptClients(int uid) {
        while (running) {
            try (LocalSocket socket = server.accept()) {
                if (!running) break;
                int peer = socket.getPeerCredentials().getUid();
                if (peer != appUid && peer != 2000 && peer != uid) continue;
                socket.setSoTimeout(1000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String command = input.readLine();
                PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
                if ("STOP".equals(command)) {
                    output.println("Stopped");
                    stop();
                } else if ("STATUS".equals(command)) {
                    output.println(statusLine());
                } else if ("VERSION".equals(command)) {
                    output.println(VERSION);
                } else output.println("Unknown command");
            } catch (IOException e) {
                if (running) System.err.println("Client: " + e);
            }
        }
    }

    private String statusLine() {
        return state + "|" + launches + "|" + last;
    }

    private synchronized void publishStatus(String status, boolean stopped) throws IOException {
        if (running || stopped) mailbox.publish(status, System.currentTimeMillis());
    }

    private void watchMailbox() {
        FileObserver observer = new FileObserver(MAILBOX_DIR,
            FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (event == FileObserver.MOVED_TO && Mailbox.STOP_REQUEST.equals(path)) signal(Change.STOP);
                else if (event == FileObserver.MOVED_TO && Mailbox.PREVIEW_REQUEST.equals(path)) signal(Change.PREVIEW);
                else if (event == FileObserver.CREATE && Mailbox.STATUS_REQUEST.equals(path)) signal(Change.STATUS_REQUEST);
                else if (event == FileObserver.CLOSE_WRITE && "tile-query".equals(path)) signal(Change.TILE_QUERY);
            }
        };
        try {
            observer.startWatching();
            String published = null;
            while (running) {
                EnumSet<Change> batch = awaitChanges();
                if (batch.contains(Change.STOP) && mailbox.stopRequested()) {
                    stop();
                    break;
                }
                if (batch.contains(Change.TILE_QUERY)) answerTileQuery();
                if (batch.contains(Change.PREVIEW)) {
                    int preview = mailbox.consumePreview();
                    if (preview != 0) enqueue(preview, true);
                }
                boolean reply = batch.contains(Change.STATUS_REQUEST) && mailbox.consumeStatusRequest();
                if (reply || batch.contains(Change.STATUS_CHANGED)) {
                    String status = statusLine();
                    if (reply || !status.equals(published)) {
                        publishStatus(status, false);
                        published = status;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            System.err.println("Status storage unavailable; stopping helper: " + error);
            try { stop(); } catch (IOException ignored) { }
        } finally {
            observer.stopWatching();
        }
    }

    private void answerTileQuery() {
        File query = new File(MAILBOX_DIR, "tile-query");
        if (!query.isFile() || query.length() > 40) return;
        try (BufferedReader input = new BufferedReader(new FileReader(query))) {
            String token = input.readLine();
            if (token == null || !token.matches("[0-9a-f-]{36}")) return;
            File reply = new File(MAILBOX_DIR, "tile-reply");
            if (reply.isFile()) {
                try (BufferedReader previous = new BufferedReader(new FileReader(reply))) {
                    if (token.equals(previous.readLine())) return;
                }
            }
            String specs = QuickTile.pinnedSpecs();
            File next = new File(MAILBOX_DIR, "tile-reply.next");
            java.nio.file.Files.write(next.toPath(), java.util.Arrays.asList(token, specs));
            if (!next.renameTo(reply)) throw new IOException("Could not publish tile list");
        } catch (Exception unavailable) { System.err.println("Quick Settings list unavailable"); }
    }

    private void stop() throws IOException {
        running = false;
        actions.shutdownNow();
        Process reader = readerProcess;
        if (reader != null) reader.destroy();
        // Closing a server descriptor in another thread need not wake accept().
        if (Thread.currentThread() != controlThread) {
            try (LocalSocket wake = new LocalSocket()) {
                wake.connect(new LocalSocketAddress(SOCKET));
            }
        }
        server.close();
    }

    private void listen() {
        try {
            String device = KeyPress.device(command("/system/bin/getevent", "-pl"));
            if (device == null) throw new IOException("Plus Key input device not found");
            ScheduledThreadPoolExecutor gestureTimer = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "PlusKey-gestures");
                thread.setDaemon(true);
                return thread;
            });
            gestureTimer.setRemoveOnCancelPolicy(true);
            KeyPress key = new KeyPress(gestureTimer, gesture -> enqueue(gesture, false), mailbox::activeGestures,
                mailbox::clickInterval, this::released, capture::append, () -> capture.active() != null);
            try {
                readKeys(device, key);
            }
            finally { key.reset(); gestureTimer.shutdownNow(); released(); }
            if (running) throw new IOException("Input reader stopped");
        } catch (Exception e) {
            if (running) {
                state = "Error";
                last = clean(e.getMessage());
                signal(Change.STATUS_CHANGED);
                System.err.println(e);
            }
        } finally {
            Process reader = readerProcess;
            if (reader != null) reader.destroy();
        }
    }

    private void listening(String device) {
        state = "Listening";
        signal(Change.STATUS_CHANGED);
        System.out.println("Listening on " + device);
    }

    private void readKeys(String device, KeyPress key) throws IOException {
        FileInputStream events;
        try {
            events = new FileInputStream(device);
        } catch (FileNotFoundException restricted) {
            // Some firmware grants its getevent executable access to input devices.
            Process reader = new ProcessBuilder("/system/bin/getevent", "-lt", device).redirectErrorStream(true).start();
            readerProcess = reader;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
                listening(device);
                String line;
                while (running && (line = input.readLine()) != null) key.gesture(line);
            }
            return;
        }
        try (FileInputStream input = events) {
            listening(device);
            InputEvents.read(input, android.os.Process.is64Bit(), key::gesture);
        }
    }

    private boolean customLongPressEnabled() throws Exception {
        try {
            String value = DeviceActions.nativeLongPressAction();
            if (value != null) return "nothing".equals(value);
        } catch (Exception unavailable) {
            // Some builds restrict access to vendor settings through the resolver.
        }
        return "nothing".equals(command("/system/bin/settings", "get", "system",
            ONEPLUS_ACTION_SETTING).trim());
    }

    private boolean isAwakeAndUnlocked() throws Exception {
        try {
            return DeviceActions.isAwakeAndUnlocked();
        } catch (Exception unavailable) {
            return Policy.mayLaunch(command("/system/bin/dumpsys", "window", "policy"));
        }
    }

    private static void wakeDisplay() throws Exception {
        try {
            if (DeviceActions.screenOn()) return;
        } catch (Exception unavailable) {
            // Keep wake support on firmware that restricts display queries.
        }
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            try {
                String reply = command("/system/bin/cmd", "power", "wakeup");
                if (reply.trim().isEmpty()) return;
            } catch (IOException unsupported) {
                // Vendor builds may omit the power shell command.
            }
        }
        command("/system/bin/input", "keyevent", "KEYCODE_WAKEUP");
    }

    private void released() {
        releases.incrementAndGet();
        try { actions.execute(this::endHold); } catch (RejectedExecutionException ignored) { }
    }

    private synchronized void endHold() {
        String owned = heldTarget;
        heldTarget = null;
        if (owned == null) return;
        try {
            if (owned.startsWith("tile:")) QuickTile.end(owned); else DeviceActions.endHold(owned);
            last = "Held action finished";
        } catch (Exception error) { recordLaunchFailure(error); }
        signal(Change.STATUS_CHANGED);
    }

    private synchronized void startHold(String target, long generation) throws Exception {
        if (!running || releases.get() != generation) return;
        boolean owned = target.startsWith("tile:") ? QuickTile.begin(target) : DeviceActions.beginHold(target);
        if (owned) heldTarget = target;
        last = owned ? "Held action started" : "Already active; leaving unchanged";
        if (owned) launches++;
        if (!running || releases.get() != generation) endHold();
    }

    private void launch(int gesture, boolean preview, long generation) {
        try {
            String target = mailbox.selected(gesture);
            if (gesture != KeyPress.SHORT && target == null) return;
            if (GesturePatterns.text(gesture).contains("-") && !customLongPressEnabled()) {
                last = "Long press needs OnePlus action set to Do nothing";
                return;
            }
            if (target == null) throw new IOException("No app selected; open Plus Key and choose an app");
            if (!preview && pendingUnlock != null) pendingUnlock.finish();
            String assignment = target;
            if (target.startsWith("hold:")) {
                String action = target.substring(5);
                if (preview) { last = "Use the physical button to test a held action"; return; }
                if (Policy.mayRunWhileLocked(action) || isAwakeAndUnlocked()) startHold(action, generation);
                else last = "Unlock first to use this held action";
                return;
            }
            String[] branches = Options.mediaBranches(target);
            if (branches != null) target = branches[DeviceActions.mediaPlaying() ? 0 : 1];
            if ((!preview && Policy.mayRunWhileLocked(target)) || isAwakeAndUnlocked()) {
                if (!preview && Policy.shouldWakeScreen(target)) {
                    wakeDisplay();
                }
                runTarget(target);
            } else if (preview) {
                last = "Skipped preview: phone locked, asleep, or state unavailable";
            } else {
                pendingUnlock = new PendingUnlock(target, assignment, gesture);
                pendingUnlock.start();
            }
        } catch (Exception e) {
            recordLaunchFailure(e);
        } finally {
            signal(Change.STATUS_CHANGED);
        }
    }

    // Callback delivery and all pending-action state use the same action executor.
    private final class PendingUnlock {
        final String target;
        final int gesture;
        long deadline;
        Runnable unwatch;
        ScheduledFuture<?> timeout;

        final String assignment;

        PendingUnlock(String target, String assignment, int gesture) {
            this.target = target;
            this.assignment = assignment;
            this.gesture = gesture;
        }

        void start() throws Exception {
            try {
                wakeDisplay();
                unwatch = DeviceActions.watchUnlock(actions, this::check);
                last = "Waiting for unlock";
                deadline = SystemClock.elapsedRealtime() + UNLOCK_TIMEOUT_MS;
                timeout = actions.schedule(this::expire, UNLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception error) {
                finish();
                throw error;
            }
            // Recheck after registration in case the phone unlocked during setup.
            check();
        }

        void finish() {
            pendingUnlock = null;
            if (timeout != null) timeout.cancel(false);
            if (unwatch != null) {
                try {
                    unwatch.run();
                } catch (RuntimeException error) {
                    System.err.println("Could not unregister unlock callbacks: " + error);
                }
            }
        }

        void expire() {
            if (!running || pendingUnlock != this) return;
            last = "Skipped: unlock timed out";
            finish();
            signal(Change.STATUS_CHANGED);
        }

        void check() {
            if (!running || pendingUnlock != this) return;
            try {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    last = "Skipped: unlock timed out";
                } else if (!assignment.equals(mailbox.selected(gesture))) {
                    last = "Skipped: action changed while waiting for unlock";
                } else if (!isAwakeAndUnlocked()) {
                    return;
                } else if (GesturePatterns.text(gesture).contains("-") && !customLongPressEnabled()) {
                    last = "Long press needs OnePlus action set to Do nothing";
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    last = "Skipped: unlock timed out";
                } else {
                    runTarget(target);
                }
            } catch (Exception error) {
                recordLaunchFailure(error);
            }
            finish();
            signal(Change.STATUS_CHANGED);
        }
    }

    private void runTarget(String target) throws Exception {
        if (!running) return;
        if (target.startsWith("tile:")) QuickTile.click(target);
        else if (target.startsWith("action:")) DeviceActions.run(target);
        else for (android.content.Intent intent : Targets.intents(target)) {
            if (target.startsWith("voice:")) intent.putExtra(android.speech.RecognizerIntent.EXTRA_SECURE, !isAwakeAndUnlocked());
            String result = command("/system/bin/am", "start", "--user", "0",
                intent.toUri(android.content.Intent.URI_INTENT_SCHEME));
            if (result.contains("Error") || result.contains("Exception")) throw new IOException(result);
        }
        try { DeviceActions.feedback(); }
        catch (Exception e) { System.err.println("Feedback unavailable: " + e.getClass().getSimpleName()); }
        launches++;
        last = "Action completed";
        System.out.println(last + " (" + launches + ")");
    }

    private void recordLaunchFailure(Exception error) {
        if (!running) return;
        last = "Launch failed: " + clean(error.getMessage());
        System.err.println(last);
    }

    static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() < 262144) output.write(buffer, 0, count);
                }
            } catch (IOException ignored) { }
        });
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Command timed out: " + args[0]);
            drain.join(1000);
            String result = output.toString("UTF-8");
            if (process.exitValue() != 0) throw new IOException(clean(result));
            return result;
        } finally { process.destroy(); }
    }

    private static String clean(String value) {
        if (value == null) return "Unknown error";
        value = value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        return value.substring(0, Math.min(240, value.length()));
    }
}
