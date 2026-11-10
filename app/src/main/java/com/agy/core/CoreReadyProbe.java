package com.agy.core;

import com.agy.util.ProcessUtils;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** One-shot TLS handshake probe of the core's loopback HTTPS port. */
public final class CoreReadyProbe {

    public static final long READY_TIMEOUT_MS = 60000;
    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 80;

    private static volatile SSLSocketFactory trustAllFactory;

    private CoreReadyProbe() {
    }

    private static SSLSocketFactory getTrustAllFactory() throws Exception {
        if (trustAllFactory != null) {
            return trustAllFactory;
        }
        synchronized (CoreReadyProbe.class) {
            if (trustAllFactory == null) {
                TrustManager[] trustAll = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                trustAllFactory = sc.getSocketFactory();
            }
        }
        return trustAllFactory;
    }

    public static boolean probeOnce(int port) {
        SSLSocket sslSocket = null;
        try {
            SSLSocketFactory factory = getTrustAllFactory();
            sslSocket = (SSLSocket) factory.createSocket();
            sslSocket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            sslSocket.setSoTimeout(600);
            sslSocket.startHandshake();
            return true;
        } catch (Exception notReady) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        } finally {
            if (sslSocket != null) {
                try {
                    sslSocket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
