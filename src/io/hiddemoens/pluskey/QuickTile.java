package io.hiddemoens.pluskey;

import android.content.ComponentName;
import java.io.IOException;
import java.util.regex.*;

final class QuickTile {
    private static final Pattern TILE_STATE = Pattern.compile(",state=([012])(?:,|\\])");
    private static long heldClickMs;
    private static String spec(String target) {
        ComponentName component = ComponentName.unflattenFromString(target.substring(5));
        if (component == null) throw new IllegalArgumentException("Invalid tile component");
        return "custom(" + component.flattenToShortString() + ")";
    }
    static String pinnedSpecs() throws Exception {
        try {
            String specs = DeviceActions.pinnedTiles();
            if (specs != null) return specs;
        } catch (Exception restricted) { /* Try the system settings command on this firmware. */ }
        return Helper.command("/system/bin/settings", "get", "secure", "sysui_qs_tiles").trim();
    }
    static void click(String target) throws Exception {
        String tile = spec(target);
        String pinned = pinnedSpecs();
        java.util.List<String> specs = java.util.Arrays.asList(pinned.split(","));
        if (!pinned.equals("null") && !pinned.isEmpty() && !specs.contains(tile)
                && !specs.contains("custom(" + target.substring(5) + ")"))
            throw new IOException("Open Quick Settings → Edit and add this app’s tile first");
        String reply = Helper.command("/system/bin/cmd", "statusbar", "click-tile", target.substring(5));
        if (!reply.trim().isEmpty()) throw new IOException("SystemUI could not click this tile");
    }
    private static int state(String target) throws Exception {
        String dump = Helper.command("/system/bin/dumpsys", "activity", "service", "com.android.systemui/.SystemUIService", "QSTileHost");
        String prefix = "[spec=" + spec(target) + ",";
        for (String line : dump.split("\n")) {
            if (!line.contains(prefix) || !line.contains("expandedAccessibilityClassName=android.widget.Switch,")) continue;
            Matcher match = TILE_STATE.matcher(line);
            if (match.find()) return Integer.parseInt(match.group(1));
        }
        return -1;
    }
    static boolean begin(String target) throws Exception {
        int state = state(target);
        if (state == 2) return false;
        if (state != 1) throw new IOException("Tile state unavailable; use normal activation");
        click(target);
        heldClickMs = android.os.SystemClock.elapsedRealtime();
        return true;
    }
    static void end(String target) throws Exception {
        // SystemUI exposes no change callback for another app's tile. On a quick
        // release give the first click one settling window before checking ownership.
        long remaining = 450 - (android.os.SystemClock.elapsedRealtime() - heldClickMs);
        if (remaining > 0) java.util.concurrent.TimeUnit.MILLISECONDS.sleep(remaining);
        if (state(target) == 2) {
            click(target);
            return;
        }
        throw new IOException("Could not confirm tile switched on; check Quick Settings");
    }
}
