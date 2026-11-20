package io.hiddemoens.pluskey;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.muntashirakon.adb.AdbStream;

final class WirelessController {
    private static WirelessController instance;
    static synchronized WirelessController get(Context context) {
        if (instance == null) instance = new WirelessController(context.getApplicationContext());
        return instance;
    }
    final Context context;
    final LocalDiscovery discovery;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.Set<Runnable> listeners = new java.util.HashSet<>();
    private final Runnable notifyListeners = () -> {
        for (Runnable listener : new java.util.ArrayList<>(listeners)) listener.run();
    };
    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        main.removeCallbacks(notifyListeners);
        main.post(notifyListeners);
    }
    private void stage(String value) {
        message = value;
        changed();
    }

    final AtomicBoolean busy = new AtomicBoolean();
    volatile String message = "";
    volatile String permissionResult="";
    private static final String CHANNEL = "wireless_setup";
    private static final long STATUS_RESPONSE_TIMEOUT_MS = 3_500;
    private WirelessController(Context context) {
        this.context = context; discovery = new LocalDiscovery(context);
        if(prefs().getInt("automatic_consent_revision",0)<2)prefs().edit().putBoolean("automatic_requested",false).apply();
        prefs().edit().remove("connect_port").apply();
    }
    SharedPreferences prefs() { return context.getSharedPreferences("setup", Context.MODE_PRIVATE); }
    boolean paired() { return prefs().getBoolean("paired", false); }
    boolean canControlDebugging() { return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==android.content.pm.PackageManager.PERMISSION_GRANTED; }
    boolean automatic() { return prefs().getBoolean("automatic_debugging",false) && canControlDebugging(); }
    boolean canUseWireless() {
        return paired() && (Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(
            "android.permission.ACCESS_LOCAL_NETWORK") == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }
    void disableAutomatic() { prefs().edit().putBoolean("automatic_debugging",false).putBoolean("automatic_requested",false).apply(); }
    private void setDebugging(boolean enabled)throws IOException {
        int current = android.provider.Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", -1);
        if (current == (enabled ? 1 : 0)) return;
        if (!android.provider.Settings.Global.putInt(context.getContentResolver(),"adb_wifi_enabled",enabled?1:0))throw new IOException("Could not change Wireless debugging");
    }
    void start() { operation(() -> {
        Mailbox box=new Mailbox(context.getExternalFilesDir(null));
        boolean shizuku = ShizukuConnection.authorized();
        if(Helper.VERSION.equals(box.runningVersion())&&(shizuku||!prefs().getBoolean("automatic_requested",false))){
            stage("Checking helper…");
            String current = requestHelperStatus(box);
            if(current!=null&&current.startsWith("Listening|")){
                if(!shizuku&&automatic())setDebugging(false);
                stage("Helper ready");return;
            }
        }
        if (shizuku) {
            if (box.selected(KeyPress.SHORT) == null) throw new IOException("Choose a short-press app first");
            stage("Starting with Shizuku…");
            try {
                ShizukuConnection.start(context);
                stage("Helper ready");
                return;
            } catch (Exception error) {
                boolean debuggingEnabled = android.provider.Settings.Global.getInt(
                    context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
                if (!canUseWireless() || (!automatic() && !debuggingEnabled)) {
                    String reason = error.getMessage();
                    throw new IOException(reason == null ? "Shizuku startup timed out" : "Shizuku could not start helper: " + reason, error);
                }
            }
        }
        if (!canUseWireless()) throw new IOException("Start and authorize Shizuku or set up Wireless debugging");
        startHelper();
    }, null); }
    String requestHelperStatus(Mailbox box) throws IOException, InterruptedException {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) return null;
        try (MailboxEvents.Reply response = new MailboxEvents.Reply(directory, Mailbox.STATUS_FILE)) {
            box.requestStatus();
            return response.await(STATUS_RESPONSE_TIMEOUT_MS, () -> box.readStatus(System.currentTimeMillis()), value -> true);
        }
    }
    void pair(String code, int manualPort, Runnable finished) {
        if (!Options.validCode(code)) { stage("Enter six-digit pairing code"); if (finished != null) finished.run(); return; }
        operation(() -> {
            int port = manualPort > 0 ? manualPort : awaitPort(true);
            stage("Pairing with this phone…");
            AdbIdentity identity = new AdbIdentity(context);
            try {
                if (!identity.pair("127.0.0.1", port, code)) throw new IOException("Pairing rejected");
                prefs().edit().putBoolean("paired", true).apply();
            } finally { identity.disconnect(); }
            stage("Paired. Starting helper…");
            startHelper();
        }, finished);
    }
    private void operation(Work work, Runnable finished) {
        if (!busy.compareAndSet(false, true)) { if (finished != null) finished.run(); return; }
        stage("Connecting to this phone…");
        worker.execute(() -> {
            try { work.run(); }
            catch (Throwable error) {
                // Do not log pairing code, private key, or raw authentication errors.
                String reason = error.getMessage();
                if (reason == null || reason.length() > 200) reason = error.getClass().getSimpleName();
                stage("Setup needed: " + reason + ". Open guided setup to retry.");
            } finally {
                busy.set(false);
                changed();
                if ("Helper ready".equals(message)) context.getSystemService(NotificationManager.class).cancel(10);
                if (finished != null) { try { notifyResult(); } finally { finished.run(); } }
            }
        });
    }
    private void startHelper() throws Exception {
        AutomaticDebugging.run(this::automatic,this::setDebugging,this::connectAndStartHelper);
    }
    private void connectAndStartHelper() throws Exception {
        Mailbox box = new Mailbox(context.getExternalFilesDir(null));
        if (box.selected(KeyPress.SHORT) == null) throw new IOException("Choose a short-press app first");
        int port = awaitPort(false);
        stage("Starting helper…");
        AdbIdentity identity = new AdbIdentity(context);
        try {
            if (!identity.connect("127.0.0.1", port)) throw new IOException("Connection refused; check Wireless debugging");
            if(prefs().getBoolean("automatic_requested",false)){
                if(!canControlDebugging())permissionResult=shell(identity,"pm grant --user 0 io.hiddemoens.pluskey android.permission.WRITE_SECURE_SETTINGS");
                if(!canControlDebugging()){
                    prefs().edit().putBoolean("automatic_requested",false).putBoolean("permission_monitor_blocked",permissionResult.contains("GRANT_RUNTIME_PERMISSIONS")).apply();
                    throw new IOException("Phone blocked automatic connection permission. Manual startup is still available");
                }
                prefs().edit().putBoolean("automatic_debugging",true).putBoolean("automatic_requested",false).apply();
                prefs().edit().putBoolean("permission_monitor_blocked",false).apply();
            }
            String source = context.getApplicationInfo().sourceDir;
            String control = "CLASSPATH=" + quote(source) + " app_process / io.hiddemoens.pluskey.Control ";
            String version = shell(identity, control + "VERSION").trim();
            if (Helper.VERSION.equals(version) && shell(identity, control + "STATUS").startsWith("Listening|")) {
                stage("Helper ready");
                return;
            }
            File dir = context.getExternalFilesDir(null);
            if (dir == null) throw new IOException("Helper storage unavailable");
            if (version.matches("[0-9]+(?:\\.[0-9]+)*")) {
                try (MailboxEvents.Reply stopped = new MailboxEvents.Reply(dir, Mailbox.STATUS_FILE)) {
                    if (shell(identity, control + "STOP").trim().equals("Stopped")) stopped.awaitStopped(box);
                }
            }
            String copy = shell(identity, "cp " + quote(source) + " /data/local/tmp/plus-lens-helper.apk.next && chmod 444 /data/local/tmp/plus-lens-helper.apk.next && mv -f /data/local/tmp/plus-lens-helper.apk.next /data/local/tmp/plus-lens-helper.apk && echo COPIED");
            if (!copy.contains("COPIED")) throw new IOException("Could not prepare helper");
            int uid = context.getApplicationInfo().uid;
            try (MailboxEvents.Reply started = new MailboxEvents.Reply(dir, Mailbox.STATUS_FILE)) {
                shell(identity, "CLASSPATH=/data/local/tmp/plus-lens-helper.apk /system/bin/setsid /system/bin/nohup /system/bin/app_process / io.hiddemoens.pluskey.Helper " + uid
                    + " </dev/null >/data/local/tmp/plus-lens.log 2>&1 &");
                started.awaitReady(box);
                stage("Helper ready");
            }
        } finally {
            identity.disconnect();
        }
    }
    private int awaitPort(boolean pairing) throws Exception {
        stage(pairing ? "Waiting for Android pairing window…" : "Waiting for connection. Approve your Wi-Fi network if Android asks.");
        int port = discovery.awaitPort(pairing, !pairing && automatic() ? 30_000 : 8_000);
        if (port > 0) return port;
        throw new IOException(pairing ? "Keep Pair device with pairing code window open" : "Enable Wireless debugging; use manual connection port if discovery fails");
    }
    private String shell(AdbIdentity identity, String command) throws Exception {
        io.github.muntashirakon.adb.AdbConnection connection = identity.getAdbConnection();
        Thread operation = Thread.currentThread();
        // Close captured transport directly: openStream may hold manager lock.
        ScheduledFuture<?> deadline = timer.schedule(() -> { operation.interrupt(); try { if(connection!=null)connection.close(); } catch (IOException ignored) { } }, 8, TimeUnit.SECONDS);
        try (AdbStream stream = identity.openStream("shell,raw:" + command); InputStream input = stream.openInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[2048]; int size;
            while ((size = input.read(bytes)) != -1) {
                if (output.size() > 32768) throw new IOException("Unexpected helper response");
                output.write(bytes, 0, size);
            }
            return output.toString("UTF-8");
        } finally { deadline.cancel(false); }
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    void pairingNotification() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Helper setup", NotificationManager.IMPORTANCE_HIGH));
        Intent intent = new Intent(context, PairingReceiver.class).setAction("PAIR");
        PendingIntent action = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        RemoteInput input = new RemoteInput.Builder("code").setLabel("Six-digit pairing code").build();
        Notification.Action reply = new Notification.Action.Builder(null, "Enter pairing code", action).addRemoteInput(input).build();
        manager.notify(10, new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Pair Plus Key").setContentText("In Wireless debugging, open Pair device with pairing code. Enter code here.")
            .setStyle(new Notification.BigTextStyle().bigText("Keep Android’s pairing window open. Enter its six-digit code using this notification."))
            .addAction(reply).setAutoCancel(false).build());
    }
    private void notifyResult() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if ("Helper ready".equals(message)) { manager.cancel(10); return; }
        PendingIntent open = PendingIntent.getActivity(context, 2, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(10, new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Plus Key setup needs attention").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message)).setContentIntent(open).setAutoCancel(true).build());
    }
    private interface Work { void run() throws Exception; }
}
