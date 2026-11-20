package io.hiddemoens.pluskey;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

final class ShizukuConnection {
    static final int PERMISSION_REQUEST = 50;

    static boolean running() {
        try {
            return Shizuku.pingBinder() && Shizuku.getVersion() >= 13;
        } catch (RuntimeException disconnected) {
            return false;
        }
    }

    static boolean authorized() {
        try {
            return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException disconnected) {
            return false;
        }
    }

    static void start(Context context) throws Exception {
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuLauncher.class))
                .daemon(false).processNameSuffix("shizuku_launcher").version(BuildConfig.VERSION_CODE);
        CompletableFuture<IBinder> service = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service.complete(binder);
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                service.completeExceptionally(new IOException("Shizuku disconnected"));
            }
        };
        Shizuku.bindUserService(args, connection);
        try {
            IBinder binder = service.get(8, TimeUnit.SECONDS);
            Parcel request = Parcel.obtain();
            Parcel response = Parcel.obtain();
            try {
                request.writeInterfaceToken(ShizukuLauncher.DESCRIPTOR);
                if (!binder.transact(ShizukuLauncher.START, request, response, 0)) {
                    throw new IOException("Shizuku launcher unavailable");
                }
                response.readException();
                String error = response.readString();
                if (error != null) throw new IOException(error);
            } finally {
                request.recycle();
                response.recycle();
            }
        } finally {
            try {
                Shizuku.unbindUserService(args, connection, true);
            } catch (RuntimeException disconnected) {
                // A stopped Shizuku server cannot remove the launcher.
            }
        }
    }
}
