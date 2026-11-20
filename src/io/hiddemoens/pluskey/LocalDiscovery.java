package io.hiddemoens.pluskey;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;

/** Resolve only the service needed for this stage, and accept only this phone's addresses. */
final class LocalDiscovery {
    private final NsdManager nsd;
    volatile int pairingPort, connectPort;
    private Search search;

    LocalDiscovery(Context context) { nsd = context.getSystemService(NsdManager.class); }

    synchronized int awaitPort(boolean pairing, long timeoutMs) throws InterruptedException {
        Search session = new Search(pairing);
        search = session;
        try {
            nsd.discoverServices(pairing ? "_adb-tls-pairing._tcp." : "_adb-tls-connect._tcp.",
                NsdManager.PROTOCOL_DNS_SD, session);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (search == session) {
                int port = pairing ? pairingPort : connectPort;
                if (port != 0) return port;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            return 0;
        } finally {
            search = null;
            pairingPort = connectPort = 0;
            try { nsd.stopServiceDiscovery(session); } catch (RuntimeException ignored) { }
        }
    }

    private final class Search implements NsdManager.DiscoveryListener {
        final boolean pairing;
        String localService;
        Search(boolean pairing) { this.pairing = pairing; }

        public void onDiscoveryStarted(String type) { }
        public void onDiscoveryStopped(String type) {
            synchronized (LocalDiscovery.this) {
                if (search != this) return;
                search = null;
                LocalDiscovery.this.notifyAll();
            }
        }
        public void onStartDiscoveryFailed(String type, int error) {
            onDiscoveryStopped(type);
        }
        public void onStopDiscoveryFailed(String type, int error) { }
        public void onServiceLost(NsdServiceInfo service) {
            synchronized (LocalDiscovery.this) {
                if (search == this && service.getServiceName().equals(localService)) {
                    localService = null;
                    if (pairing) pairingPort = 0; else connectPort = 0;
                }
            }
        }
        public void onServiceFound(NsdServiceInfo info) {
            synchronized (LocalDiscovery.this) { if (search != this) return; }
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo service, int error) { }
                    public void onServiceResolved(NsdServiceInfo service) {
                        try {
                            InetAddress host = service.getHost();
                            if (host == null || (!host.isLoopbackAddress() && NetworkInterface.getByInetAddress(host) == null)) return;
                            int port = service.getPort();
                            if (port < 1 || port > 65535) return;
                            synchronized (LocalDiscovery.this) {
                                if (search != Search.this) return;
                                localService = service.getServiceName();
                                if (pairing) pairingPort = port; else connectPort = port;
                                LocalDiscovery.this.notifyAll();
                            }
                        } catch (Exception ignored) { }
                    }
                });
            } catch (RuntimeException ignored) { }
        }
    }
}
