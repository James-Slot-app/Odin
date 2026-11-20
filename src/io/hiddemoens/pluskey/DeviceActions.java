package io.hiddemoens.pluskey;

import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.camera2.*;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.os.*;
import android.net.wifi.WifiManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.util.List;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;
import java.util.concurrent.*;

/** Invoked only by the already-authorized shell helper, using shell attribution. */
final class DeviceActions {
    private static final String OPLUS_DATA_SLOT = "oplus_customize_multi_sim_network_primary_slot";
    private static Context context;
    private static String cameraId;
    private static TorchWatch heldTorch;
    private static final Binder recordingClient = new Binder();
    private static String heldRecording;

    private static synchronized Context context() throws Exception {
        if (context == null) {
            FutureTask<Context> initialize = new FutureTask<>(DeviceActions::createShellContext);
            new Handler(Looper.getMainLooper()).post(initialize);
            try {
                context = initialize.get();
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
                throw failed;
            }
        }
        return context;
    }

    private static Context createShellContext() throws Exception {
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Object system=thread.getMethod("systemMain").invoke(null);
        Context base=(Context)thread.getMethod("getSystemContext").invoke(system);
        Context shell = base.createPackageContext("com.android.shell", 0);
        // createPackageContext inherits the system context's "android" operation attribution.
        // Construct an app context for the package that actually owns this process UID.
        java.lang.reflect.Field info = base.getClass().getDeclaredField("mPackageInfo");
        info.setAccessible(true);
        java.lang.reflect.Method create = base.getClass().getDeclaredMethod("createAppContext",
            thread, Class.forName("android.app.LoadedApk"));
        create.setAccessible(true);
        return (Context) create.invoke(null, system, info.get(shell));
    }
    static String pinnedTiles() throws Exception {
        return Settings.Secure.getString(context().getContentResolver(), "sysui_qs_tiles");
    }
    static String nativeLongPressAction() throws Exception {
        return Settings.System.getString(context().getContentResolver(), Helper.ONEPLUS_ACTION_SETTING);
    }
    static boolean screenOn() throws Exception {
        Context ctx = context();
        if (!ctx.getSystemService(PowerManager.class).isInteractive()) return false;
        Display display = ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Default display unavailable");
        return display.getState() == Display.STATE_ON;
    }
    static boolean isAwakeAndUnlocked() throws Exception {
        return screenOn() && !context().getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    static Runnable watchUnlock(Executor executor, Runnable changed) throws Exception {
        if (Build.VERSION.SDK_INT < 36) {
            throw new IllegalStateException("Unlock callbacks require Android 16 or later");
        }
        Context ctx = context();
        DisplayManager displays = ctx.getSystemService(DisplayManager.class);
        KeyguardManager keyguard = ctx.getSystemService(KeyguardManager.class);
        DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (id == Display.DEFAULT_DISPLAY) changed.run();
            }
        };
        KeyguardManager.KeyguardLockedStateListener keyguardListener = locked -> changed.run();
        displays.registerDisplayListener(executor, DisplayManager.EVENT_TYPE_DISPLAY_STATE, displayListener);
        try {
            keyguard.addKeyguardLockedStateListener(executor, keyguardListener);
        } catch (RuntimeException error) {
            displays.unregisterDisplayListener(displayListener);
            throw error;
        }
        return () -> {
            try {
                keyguard.removeKeyguardLockedStateListener(keyguardListener);
            } finally {
                displays.unregisterDisplayListener(displayListener);
            }
        };
    }
    static boolean mediaPlaying() throws Exception {
        return context().getSystemService(AudioManager.class).isMusicActive();
    }

    static synchronized boolean beginHold(String target) {
        try {
            if ("action:record".equals(target)) {
                if (heldRecording != null) return false;
                heldRecording = recording(true);
                return heldRecording != null;
            }
            if (!"action:torch".equals(target)) return false;
            if (heldTorch != null) return false;
            TorchWatch torch = openTorch(context());
            try {
                synchronized (torch) {
                    if (!torch.state.canHold()) return false;
                    torch.set(true, true);
                    heldTorch = torch;
                    return true;
                }
            } finally {
                if (heldTorch != torch) torch.close();
            }
        } catch (Exception error) {
            System.err.println("Cannot start hold: " + error.getMessage());
            return false;
        }
    }

    static synchronized void endHold(String target) {
        try {
            if ("action:record".equals(target) && heldRecording != null) {
                recordingCommand("stop", heldRecording, null);
                heldRecording = null;
            } else if ("action:torch".equals(target) && heldTorch != null) {
                try (TorchWatch torch = heldTorch) {
                    heldTorch = null;
                    synchronized (torch) {
                        if (torch.state.held) torch.set(false, false);
                    }
                }
            }
        } catch (Exception error) { System.err.println("Cannot end hold: " + error.getMessage()); }
    }

    private static Bundle recordingCommand(String command, String token, Bundle extras) throws Exception {
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        Object manager = android.app.ActivityManager.class.getMethod("getService").invoke(null);
        Binder handle = new Binder();
        String authority = RecordingProvider.AUTHORITY;
        Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
            .invoke(manager, authority, 0, handle, "PlusKey");
        if (holder == null) throw new IllegalStateException("Recorder unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Class<?> providerType = Class.forName("android.content.IContentProvider");
            String caller = android.os.Process.myUid() == 2000 ? "com.android.shell" : "root";
            Bundle result;
            if (Build.VERSION.SDK_INT >= 31) {
                android.content.AttributionSource attribution = new android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(caller).build();
                result = (Bundle) providerType.getMethod("call", android.content.AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, attribution, authority, command, token, extras);
            } else {
                result = (Bundle) providerType.getMethod("call", String.class, String.class, String.class, String.class, String.class, Bundle.class)
                    .invoke(provider, caller, null, authority, command, token, extras);
            }
            if (result == null) throw new IllegalStateException("Recorder unavailable");
            return result;
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException(error.getCause().getMessage(), error.getCause());
        } finally {
            managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class).invoke(manager, authority, handle, 0);
        }
    }

    private static String recording(boolean hold) throws Exception {
        String token = java.util.UUID.randomUUID().toString();
        Bundle extras = new Bundle();
        extras.putBinder("helper", recordingClient);
        boolean reserved = false;
        boolean started = false;
        try {
            String status = recordingCommand(hold ? "hold" : "toggle", token, extras).getString("status");
            if ("busy".equals(status) || "stopped".equals(status)) return null;
            reserved = "reserved".equals(status);
            if (!reserved || !isAwakeAndUnlocked()) throw new IllegalStateException("Unlock phone to record");
            String result = Helper.command("/system/bin/am", "start", "--user", "0", "-f", "0x18000000", "-n",
                "io.hiddemoens.pluskey/.RecordingActivity", "--es", "session", token);
            if (result.contains("Error") || result.contains("Exception")) throw new IllegalStateException("Cannot open recording window");
            started = recordingCommand("await", token, null).getBoolean("started");
            if (!started) throw new IllegalStateException("Microphone recording did not start");
            return token;
        } finally {
            if (reserved && !started) recordingCommand("stop", token, null);
        }
    }

    private static TorchWatch openTorch(Context ctx) throws Exception {
        CameraManager manager = ctx.getSystemService(CameraManager.class);
        if (cameraId == null) {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics info = manager.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    cameraId = id;
                    if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) break;
                }
            }
        }
        if (cameraId == null) throw new IllegalStateException("No flashlight available");
        TorchWatch torch = new TorchWatch(manager, cameraId);
        manager.registerTorchCallback(torch, new Handler(Looper.getMainLooper()));
        try {
            torch.initial.await(1, TimeUnit.SECONDS);
            return torch;
        } catch (InterruptedException error) {
            torch.close();
            throw error;
        }
    }

    private static final class TorchWatch extends CameraManager.TorchCallback implements AutoCloseable {
        final CameraManager manager;
        final String id;
        final LightState state = new LightState();
        final CountDownLatch initial = new CountDownLatch(1);

        TorchWatch(CameraManager manager, String id) { this.manager = manager; this.id = id; }
        @Override public void onTorchModeChanged(String id, boolean enabled) { observed(id, enabled); }
        @Override public void onTorchModeUnavailable(String id) { observed(id, null); }

        private synchronized void observed(String changedId, Boolean enabled) {
            if (!id.equals(changedId)) return;
            state.observed(enabled);
            initial.countDown();
            notifyAll();
        }

        // Called with this watch locked. Keep a held watch through release. For a toggle,
        // wait for the service acknowledgment before allowing another press to sample it.
        void set(boolean enabled, boolean hold) throws Exception {
            manager.setTorchMode(id, enabled);
            state.requested(enabled, hold);
            if (hold) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (state.changing()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        @Override public void close() { manager.unregisterTorchCallback(this); }
    }

    private static MediaSessionManager mediaSessions;

    private static MediaSessionManager mediaSessions(Context ctx) throws ReflectiveOperationException {
        if (mediaSessions == null) {
            // A standalone app_process does not inherit the media module's Zygote setup.
            if (Build.VERSION.SDK_INT >= 31) {
                Class<?> initializer = Class.forName("android.media.MediaFrameworkPlatformInitializer");
                if (initializer.getMethod("getMediaServiceManager").invoke(null) == null) {
                    Class<?> manager = Class.forName("android.media.MediaServiceManager");
                    initializer.getMethod("setMediaServiceManager", manager).invoke(null, manager.getConstructor().newInstance());
                }
            }
            mediaSessions = ctx.getSystemService(MediaSessionManager.class);
        }
        return mediaSessions;
    }

    private static void audioKey(int code) throws Exception {
        try {
            Context ctx = context();
            MediaSessionManager sessions = mediaSessions(ctx);
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                int direction = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                int flags = ctx.getSystemService(PowerManager.class).isInteractive() ? AudioManager.FLAG_SHOW_UI : 0;
                MediaSessionManager.class.getMethod("dispatchAdjustVolume", int.class, int.class, int.class)
                    .invoke(sessions, AudioManager.USE_DEFAULT_STREAM_TYPE, direction, flags);
            } else {
                java.lang.reflect.Method dispatch = MediaSessionManager.class.getMethod("dispatchMediaKeyEvent", KeyEvent.class, boolean.class);
                long time = SystemClock.uptimeMillis();
                dispatch.invoke(sessions, new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0), false);
                dispatch.invoke(sessions, new KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0), false);
            }
        } catch (ReflectiveOperationException | RuntimeException restricted) {
            Helper.command("/system/bin/input", "keyevent", Integer.toString(code));
        }
    }

    static synchronized void run(String action)throws Exception{
        String code=null;
        switch(action){
            case "action:record": recording(false); return;
            case "action:previous_app":
                previousApp(context());
                return;
            case "action:media_pause": audioKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return;
            case "action:assistant":code=String.valueOf(android.view.KeyEvent.KEYCODE_VOICE_ASSIST);break;
            case "action:screenshot":code="120";break;case "action:lock":code="223";break;
            case "action:media_toggle": audioKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
            case "action:media_next": audioKey(KeyEvent.KEYCODE_MEDIA_NEXT); return;
            case "action:media_previous": audioKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
            case "action:volume_up": audioKey(KeyEvent.KEYCODE_VOLUME_UP); return;
            case "action:volume_down": audioKey(KeyEvent.KEYCODE_VOLUME_DOWN); return;
        }
        if(code!=null){Helper.command("/system/bin/input","keyevent",code);return;}
        if(action.equals("action:notifications")||action.equals("action:quick_settings")){Helper.command("/system/bin/cmd","statusbar",action.equals("action:notifications")?"expand-notifications":"expand-settings");return;}
        Context ctx=context();
        switch (action) {
            case "action:hotspot":
                toggleHotspot(ctx);
                return;
            case "action:dnd_hour":
                android.app.NotificationManager notifications = ctx.getSystemService(android.app.NotificationManager.class);
                boolean quiet = notifications.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
                android.net.Uri until = quiet ? null : android.net.Uri.parse("condition://android/countdown/" +
                    (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)) + "/alarm/false");
                notifications.getClass().getMethod("setZenMode", int.class, android.net.Uri.class, String.class)
                    .invoke(notifications, quiet ? 0 : 1, until, "PlusKey");
                return;
            case "action:wifi_toggle":
                int state = ctx.getSystemService(WifiManager.class).getWifiState();
                if (state == WifiManager.WIFI_STATE_UNKNOWN) throw new IllegalStateException("Wi-Fi state unavailable");
                boolean enabled = state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING;
                Helper.command("/system/bin/cmd", "wifi", "set-wifi-enabled", enabled ? "disabled" : "enabled");
                return;
            case "action:data_toggle":
            case "action:data_sim":
            case "action:data_slot1":
            case "action:data_slot2":
                mobileData(ctx, action);
                return;
        }
        if(action.equals("action:torch") || action.equals("action:pause_torch")){
            TorchWatch torch = heldTorch != null ? heldTorch : openTorch(ctx);
            try {
                synchronized (torch) {
                    if (torch.state.enabled == null || torch.state.changing())
                        throw new IllegalStateException("Flashlight unavailable or changing");
                    if (action.equals("action:pause_torch") && !torch.state.enabled && mediaPlaying()) {
                        audioKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
                        return;
                    }
                    torch.set(!torch.state.enabled, false);
                    heldTorch = null;
                }
            } finally { if (heldTorch != torch) torch.close(); }
            return;
        }
        AudioManager audio=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
        int next=Options.ringerMode(action,audio.getRingerMode());
        if(next<0)throw new IllegalArgumentException("Unknown action");
        // Shell holds MODIFY_AUDIO_ROUTING; system control preserves all three modes.
        audio.getClass().getMethod("setRingerModeInternal",int.class).invoke(audio,next);
        if(audio.getRingerMode()!=next)throw new IllegalStateException("Phone did not apply sound mode");
    }
    private static void previousApp(Context ctx) {
        android.app.ActivityManager apps = ctx.getSystemService(android.app.ActivityManager.class);
        List<android.app.ActivityManager.RunningTaskInfo> visible = apps.getRunningTasks(1);
        if (visible.isEmpty()) throw new IllegalStateException("Foreground task unavailable");
        int current = visible.get(0).taskId;
        for (android.app.ActivityManager.RecentTaskInfo task : apps.getRecentTasks(32, 0)) {
            if (task.id >= 0 && task.taskId != current) {
                apps.moveTaskToFront(task.taskId, 0);
                return;
            }
        }
        throw new IllegalStateException("No previous app");
    }

    private static void toggleHotspot(Context ctx) throws Exception {
        WifiManager wifi = ctx.getSystemService(WifiManager.class);
        int state = (int) WifiManager.class.getMethod("getWifiApState").invoke(wifi);
        android.net.TetheringManager manager = ctx.getSystemService(android.net.TetheringManager.class);
        if (state == 13) { // WIFI_AP_STATE_ENABLED
            manager.getClass().getMethod("stopTethering", int.class).invoke(manager, 0);
        } else if (state == 11) { // WIFI_AP_STATE_DISABLED
            android.net.TetheringManager.TetheringRequest request =
                new android.net.TetheringManager.TetheringRequest.Builder(0).build();
            CompletableFuture<Void> started = new CompletableFuture<>();
            manager.startTethering(request, Runnable::run, new android.net.TetheringManager.StartTetheringCallback() {
                public void onTetheringStarted() { started.complete(null); }
                public void onTetheringFailed(int error) {
                    started.completeExceptionally(new IllegalStateException("Hotspot error " + error));
                }
            });
            started.get(10, TimeUnit.SECONDS);
        } else throw new IllegalStateException("Hotspot changing or unavailable");
    }

    private static void mobileData(Context ctx, String action) throws Exception {
        // app_process does not run ZygoteInit's telephony service initialization.
        if (Build.VERSION.SDK_INT >= 31) {
            Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
            if (initializer.getMethod("getTelephonyServiceManager").invoke(null) == null) {
                Class<?> manager = Class.forName("android.os.TelephonyServiceManager");
                initializer.getMethod("setTelephonyServiceManager", manager)
                    .invoke(null, manager.getConstructor().newInstance());
            }
        }
        SubscriptionManager subscriptions = ctx.getSystemService(SubscriptionManager.class);
        int current = SubscriptionManager.getDefaultDataSubscriptionId();
        if (subscriptions == null || subscriptions.getActiveSubscriptionInfo(current) == null) {
            throw new IllegalStateException("No active default data SIM");
        }
        TelephonyManager phones = ctx.getSystemService(TelephonyManager.class);
        TelephonyManager source = phones.createForSubscriptionId(current);
        boolean enabled = source.isDataEnabled();
        if (action.equals("action:data_toggle")) {
            source.setDataEnabled(!enabled);
            return;
        }

        String preference = Helper.command("/system/bin/settings", "get", "global", OPLUS_DATA_SLOT).trim();
        int preferredSlot = preference.matches("[0-9]+") ? Integer.parseInt(preference) : -1;
        int slot = action.equals("action:data_slot1") ? 0 : action.equals("action:data_slot2") ? 1 : -1;
        if (slot >= 0) {
            SubscriptionInfo chosen = subscriptions.getActiveSubscriptionInfoForSimSlotIndex(slot);
            if (chosen == null || chosen.isOpportunistic()) throw new IllegalStateException("Data SIM unavailable");
            selectDataSim(subscriptions, phones, chosen, current, enabled, preferredSlot);
            return;
        }

        // Android returns active subscriptions in SIM slot order.
        List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
        if (active == null) return;
        int selected = current;
        for (SubscriptionInfo sim : active) {
            if (!sim.isOpportunistic() && sim.getSimSlotIndex() == preferredSlot) selected = sim.getSubscriptionId();
        }
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).getSubscriptionId() != selected) continue;
            for (int offset = 1; offset < active.size(); offset++) {
                SubscriptionInfo next = active.get((i + offset) % active.size());
                if (next.isOpportunistic()) continue;
                selectDataSim(subscriptions, phones, next, current, enabled, preferredSlot);
                return;
            }
            return;
        }
    }
    private static void selectDataSim(SubscriptionManager subscriptions, TelephonyManager phones, SubscriptionInfo selected,
            int current, boolean enabled, int preferredSlot) throws Exception {
        int id = selected.getSubscriptionId();
        TelephonyManager destination = phones.createForSubscriptionId(id);
        if (id != current) SubscriptionManager.class.getMethod("setDefaultDataSubId", int.class).invoke(subscriptions, id);
        if (preferredSlot >= 0 && preferredSlot != selected.getSimSlotIndex()) {
            Helper.command("/system/bin/settings", "put", "global", OPLUS_DATA_SLOT, String.valueOf(selected.getSimSlotIndex()));
        }
        if (destination.isDataEnabled() != enabled) destination.setDataEnabled(enabled);
    }
    static void feedback()throws Exception{
        Helper.command("/system/bin/cmd","vibrator_manager","feedback","-d","PlusKey","4");
    }
    static synchronized void shutdown(){
        endHold("action:record");
        endHold("action:torch");
    }
}
