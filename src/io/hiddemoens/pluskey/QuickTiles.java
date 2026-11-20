package io.hiddemoens.pluskey;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.service.quicksettings.TileService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class QuickTiles {
    static java.util.Set<String> pinned(Context context) {
        String specs = null;
        try { specs = android.provider.Settings.Secure.getString(context.getContentResolver(), "sysui_qs_tiles"); }
        catch (SecurityException ignored) { }
        if (specs == null) {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) try { specs = requestPinned(dir); }
            catch (IOException ignored) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        java.util.Set<String> result = new java.util.HashSet<>();
        if (specs != null) for (String part : specs.split(",")) {
            String spec = part.trim();
            if (!spec.startsWith("custom(") || !spec.endsWith(")")) continue;
            ComponentName component = ComponentName.unflattenFromString(spec.substring(7,spec.length()-1));
            if (component != null) result.add(component.flattenToString());
        }
        return result;
    }
    // Older helpers also use a single query slot and echo the request token.
    private static synchronized String requestPinned(File dir) throws IOException, InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        String token = java.util.UUID.randomUUID().toString();
        File reply = new File(dir, "tile-reply");
        try (MailboxEvents.Reply changed = new MailboxEvents.Reply(dir, reply.getName())) {
            Files.write(new File(dir, "tile-query").toPath(), java.util.Collections.singletonList(token));
            return changed.await(1500, () -> {
                List<String> lines = Files.readAllLines(reply.toPath());
                return lines.size() == 2 && token.equals(lines.get(0)) ? lines.get(1) : null;
            }, value -> true);
        }
    }

    static List<ResolveInfo> discover(Context context, String owner) {
        Intent intent = new Intent(TileService.ACTION_QS_TILE).setPackage(owner);
        List<ResolveInfo> tiles = new ArrayList<>();
        for (ResolveInfo result : context.getPackageManager().queryIntentServices(intent, PackageManager.GET_META_DATA)) {
            ServiceInfo info = result.serviceInfo;
            if (info != null && info.exported && !context.getPackageName().equals(info.packageName)
                    && Manifest.permission.BIND_QUICK_SETTINGS_TILE.equals(info.permission)) tiles.add(result);
        }
        return tiles;
    }

    static boolean toggleable(Context context, ComponentName component) {
        for (ResolveInfo result : discover(context, component.getPackageName())) {
            ServiceInfo info = result.serviceInfo;
            if (component.getClassName().equals(info.name) && info.metaData != null
                    && info.metaData.getBoolean(TileService.META_DATA_TOGGLEABLE_TILE, false)) return true;
        }
        return false;
    }
}
