package com.agy.runtime;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.agy.net.DnsCollector;
import com.agy.util.AtomicFileWriter;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * The core reads "etc//resolv.conf" (cwd = getFilesDir()). Android has no
 * /etc/resolv.conf, so materialize one plus a hosts file that pins IPv4 for
 * Google API endpoints to avoid 39s IPv6 blackhole timeouts on mobile/VPN networks.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";
    private static final String[] IPV4_PIN_HOSTS = new String[]{
            "daily-cloudcode-pa.googleapis.com",
            "cloudcode-pa.googleapis.com",
            "oauth2.googleapis.com",
            "www.googleapis.com"
    };

    private NetworkConfigWriter() {
    }

    public static void write(final Context appContext) {
        final File etc = new File(appContext.getFilesDir(), "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            Log.w(TAG, "cannot create " + etc);
            return;
        }
        StringBuilder resolv = new StringBuilder();
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        AtomicFileWriter.writeText(new File(etc, "resolv.conf"), resolv.toString());

        final File hostsFile = new File(etc, "hosts");
        if (!hostsFile.isFile()) {
            AtomicFileWriter.writeText(hostsFile, "127.0.0.1 localhost\n");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread t = new Thread(() -> writeHostsWithIpv4Pins(hostsFile), "AgyHostsIpv4");
            t.setDaemon(true);
            t.start();
        } else {
            writeHostsWithIpv4Pins(hostsFile);
        }
    }

    private static void writeHostsWithIpv4Pins(File hostsFile) {
        StringBuilder hosts = new StringBuilder("127.0.0.1 localhost\n");
        for (String host : IPV4_PIN_HOSTS) {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                for (InetAddress addr : addrs) {
                    if (addr instanceof Inet4Address) {
                        String ipv4 = addr.getHostAddress();
                        if (ipv4 != null && !ipv4.isEmpty()) {
                            hosts.append(ipv4).append(' ').append(host).append('\n');
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        AtomicFileWriter.writeText(hostsFile, hosts.toString());
    }
}
