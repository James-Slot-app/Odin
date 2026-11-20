package io.hiddemoens.pluskey;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.io.File;
import java.io.IOException;

/** Shizuku UserService with one operation: start this app's listener. */
public final class ShizukuLauncher extends Binder {
    static final String DESCRIPTOR = "io.hiddemoens.pluskey.ShizukuLauncher";
    static final int START = IBinder.FIRST_CALL_TRANSACTION;
    private static final int DESTROY = 16777115;
    private final int appUid;
    private final String apk;

    public ShizukuLauncher(Context context) {
        appUid = context.getApplicationInfo().uid;
        apk = context.getApplicationInfo().sourceDir;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws android.os.RemoteException {
        if (code == DESTROY) {
            if (getCallingUid() != android.os.Process.myUid()) throw new SecurityException();
            System.exit(0);
            return true;
        }
        if (code != START) return super.onTransact(code, data, reply, flags);
        if (getCallingUid() != appUid) throw new SecurityException();
        data.enforceInterface(DESCRIPTOR);
        String error = null;
        try {
            startHelper();
        } catch (IOException failure) {
            error = failure.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "Shizuku startup interrupted";
        }
        reply.writeNoException();
        reply.writeString(error);
        return true;
    }

    private synchronized void startHelper() throws IOException, InterruptedException {
        String version = control("VERSION");
        if (Helper.VERSION.equals(version) && control("STATUS").startsWith("Listening|")) return;
        File dir = Helper.MAILBOX_DIR;
        Mailbox box = new Mailbox(dir);
        if (!version.isEmpty()) {
            try (MailboxEvents.Reply stopped = new MailboxEvents.Reply(dir, Mailbox.STATUS_FILE)) {
                Control.request("STOP");
                stopped.awaitStopped(box);
            }
        }
        File discard = new File("/dev/null");
        ProcessBuilder process = new ProcessBuilder("/system/bin/setsid", "/system/bin/app_process",
                "/", Helper.class.getName(), Integer.toString(appUid), "shizuku")
                .redirectInput(discard).redirectOutput(discard).redirectError(discard);
        process.environment().put("CLASSPATH", apk);
        try (MailboxEvents.Reply started = new MailboxEvents.Reply(dir, Mailbox.STATUS_FILE)) {
            Process helper = process.start();
            try {
                started.awaitReady(box);
            } catch (IOException | InterruptedException error) {
                helper.destroy();
                throw error;
            }
        }
    }

    private static String control(String command) {
        try {
            return Control.request(command);
        } catch (IOException unavailable) {
            return "";
        }
    }
}
