package com.agy.ui.webview;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.agy.util.LoopbackHost;
import com.agy.util.MimeTypes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxies loopback HTTP UI sidecar GET requests (e.g. {@code http://localhost:<port>})
 * so {@code SameSite=Lax} session cookies set by {@code agy_sidecar_*} gateways are
 * preserved when embedded inside the {@code https://127.0.0.1:<enginePort>} host WebView.
 */
public final class SidecarLoopbackProxy {

    private static final String TAG = "SidecarProxy";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TRACKED_PORTS = 32;
    private static final long SESSION_TTL_MS = 2L * 60L * 60L * 1000L;

    private static final class CookieEntry {
        final String value;
        final long expiresAtMs; // <= 0 means session cookie (expires with PortSession TTL)

        CookieEntry(String value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isExpired(long now) {
            return expiresAtMs > 0L && now >= expiresAtMs;
        }
    }

    private static final class PortSession {
        volatile String token;
        volatile long lastAccessMs;
        final ConcurrentHashMap<String, CookieEntry> cookies = new ConcurrentHashMap<>();

        PortSession() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<Integer, PortSession> SESSIONS_BY_PORT =
            new ConcurrentHashMap<>();

    private SidecarLoopbackProxy() {
    }

    private static PortSession getOrCreateSession(int port, boolean create) {
        long now = System.currentTimeMillis();
        PortSession existing = SESSIONS_BY_PORT.get(port);
        if (existing != null) {
            if (now - existing.lastAccessMs > SESSION_TTL_MS) {
                SESSIONS_BY_PORT.remove(port, existing);
                existing = null;
            } else {
                existing.lastAccessMs = now;
                return existing;
            }
        }
        if (!create) {
            return null;
        }
        evictExpiredOrOverflowSessions(now);
        PortSession created = new PortSession();
        PortSession prev = SESSIONS_BY_PORT.putIfAbsent(port, created);
        PortSession active = prev != null ? prev : created;
        active.lastAccessMs = now;
        return active;
    }

    private static void evictExpiredOrOverflowSessions(long now) {
        for (Map.Entry<Integer, PortSession> e : SESSIONS_BY_PORT.entrySet()) {
            PortSession s = e.getValue();
            if (s == null || now - s.lastAccessMs > SESSION_TTL_MS) {
                SESSIONS_BY_PORT.remove(e.getKey(), s);
            }
        }
        while (SESSIONS_BY_PORT.size() >= MAX_TRACKED_PORTS) {
            Integer oldestPort = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<Integer, PortSession> e : SESSIONS_BY_PORT.entrySet()) {
                PortSession s = e.getValue();
                if (s != null && s.lastAccessMs < oldestTs) {
                    oldestTs = s.lastAccessMs;
                    oldestPort = e.getKey();
                }
            }
            if (oldestPort == null) {
                break;
            }
            SESSIONS_BY_PORT.remove(oldestPort);
        }
    }

    private static String safeLogUri(Uri uri) {
        if (uri == null) return "";
        String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
        String host = uri.getHost() != null ? uri.getHost() : "localhost";
        int port = uri.getPort();
        String path = uri.getPath() != null ? uri.getPath() : "/";
        return scheme + "://" + host + (port > 0 ? ":" + port : "") + path;
    }

    public static WebResourceResponse intercept(Uri uri, String method,
                                                Map<String, String> reqHeaders, int enginePort) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (!LoopbackHost.isLoopback(uri.getHost())) {
            return null;
        }
        int port = uri.getPort();
        if (port <= 0 || port == enginePort) {
            return null;
        }
        if (reqHeaders != null) {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                if ("accept".equalsIgnoreCase(e.getKey()) && e.getValue() != null
                        && e.getValue().toLowerCase(Locale.US).contains("text/event-stream")) {
                    return null;
                }
            }
        }

        String urlToken = null;
        try {
            urlToken = uri.getQueryParameter("token");
        } catch (Exception ignored) {
        }
        PortSession session;
        if (urlToken != null && !urlToken.isEmpty()) {
            session = getOrCreateSession(port, true);
            String prevToken = session.token;
            if (prevToken != null && !prevToken.equals(urlToken)) {
                // Port reused by a new sidecar instance: flush previous cookies.
                session.cookies.clear();
            }
            session.token = urlToken;
        } else {
            session = getOrCreateSession(port, false);
        }

        String cachedToken = session != null ? session.token : null;
        String targetUrl = buildLoopbackUrl(port, uri, null);

        try {
            HttpURLConnection conn = executeGet(targetUrl, port, reqHeaders, cachedToken);
            int status = conn.getResponseCode();
            captureSetCookies(port, conn.getHeaderFields());

            if (status == 401 && cachedToken != null && !cachedToken.isEmpty()
                    && (urlToken == null || urlToken.isEmpty())) {
                closeQuietly(conn);
                String retryUrl = buildLoopbackUrl(port, uri, cachedToken);
                conn = executeGet(retryUrl, port, reqHeaders, cachedToken);
                status = conn.getResponseCode();
                captureSetCookies(port, conn.getHeaderFields());
            }

            String rawContentType = conn.getContentType();
            String mimeType = parseMimeType(rawContentType, uri.getPath());
            String charset = parseCharset(rawContentType, mimeType);
            Map<String, String> respHeaders = extractResponseHeaders(conn.getHeaderFields());
            String reason = safeReason(conn.getResponseMessage(), status);

            InputStream rawStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (rawStream == null) {
                conn.disconnect();
                return new WebResourceResponse(mimeType, charset, status, reason,
                        respHeaders, new ByteArrayInputStream(new byte[0]));
            }

            final HttpURLConnection finalConn = conn;
            InputStream managedStream = new FilterInputStream(rawStream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        finalConn.disconnect();
                    }
                }
            };

            if (status == 200 && "text/html".equalsIgnoreCase(mimeType)
                    && cachedToken != null && !cachedToken.isEmpty()) {
                byte[] htmlPrefix = readUpTo(rawStream, MAX_HTML_BYTES + 1);
                if (htmlPrefix.length <= MAX_HTML_BYTES) {
                    closeQuietly(conn);
                    Charset cs = resolveCharset(charset);
                    String html = new String(htmlPrefix, cs);
                    String patched = injectTokenBootstrapScript(html, cachedToken);
                    byte[] outBytes = patched.getBytes(cs);
                    removeHeaderIgnoreCase(respHeaders, "Content-Length");
                    return new WebResourceResponse("text/html", cs.name(), status, reason,
                            respHeaders, new ByteArrayInputStream(outBytes));
                }
                // Large HTML (> 2 MiB): stream the already-read prefix followed by the
                // live remainder so large reports/viewers are never truncated.
                InputStream fullStream = new SequenceInputStream(
                        new ByteArrayInputStream(htmlPrefix), managedStream);
                return new WebResourceResponse("text/html", charset != null ? charset : "UTF-8",
                        status, reason, respHeaders, fullStream);
            }

            return new WebResourceResponse(mimeType, charset, status, reason,
                    respHeaders, managedStream);
        } catch (Exception e) {
            Log.w(TAG, "sidecar proxy pass-through for " + safeLogUri(uri) + ": " + e.getMessage());
            return null;
        }
    }

    private static Charset resolveCharset(String charsetName) {
        if (charsetName != null && !charsetName.trim().isEmpty()) {
            try {
                return Charset.forName(charsetName.trim());
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void removeHeaderIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null || target == null) return;
        Iterator<String> it = headers.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k != null && k.equalsIgnoreCase(target)) {
                it.remove();
            }
        }
    }

    private static String buildLoopbackUrl(int port, Uri uri, String extraToken) {
        String path = uri.getEncodedPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getEncodedQuery();
        if (extraToken != null && !extraToken.isEmpty()) {
            String encTok;
            try {
                encTok = URLEncoder.encode(extraToken, "UTF-8");
            } catch (Exception e) {
                encTok = extraToken;
            }
            if (query == null || query.isEmpty()) {
                query = "token=" + encTok;
            } else {
                query = query + "&token=" + encTok;
            }
        }
        return "http://127.0.0.1:" + port + path + (query != null && !query.isEmpty() ? "?" + query : "");
    }

    private static HttpURLConnection executeGet(String targetUrl, int port,
                                                Map<String, String> reqHeaders,
                                                String cachedToken) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        String incomingCookie = null;
        boolean hasSidecarTokenHeader = false;
        if (reqHeaders != null) {
            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k == null || v == null) {
                    continue;
                }
                String lower = k.toLowerCase(Locale.US);
                if ("cookie".equals(lower)) {
                    incomingCookie = v;
                } else if ("x-sidecar-token".equals(lower)) {
                    hasSidecarTokenHeader = true;
                    conn.setRequestProperty(k, v);
                } else if ("accept".equals(lower) || "accept-language".equals(lower)
                        || "user-agent".equals(lower) || "cache-control".equals(lower)
                        || "pragma".equals(lower) || "if-none-match".equals(lower)
                        || "if-modified-since".equals(lower)) {
                    conn.setRequestProperty(k, v);
                }
            }
        }

        String mergedCookie = buildCookieHeader(port, incomingCookie);
        if (mergedCookie != null && !mergedCookie.isEmpty()) {
            conn.setRequestProperty("Cookie", mergedCookie);
        }
        if (!hasSidecarTokenHeader && cachedToken != null && !cachedToken.isEmpty()) {
            conn.setRequestProperty("X-Sidecar-Token", cachedToken);
        }
        return conn;
    }

    private static void captureSetCookies(int port, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"set-cookie".equalsIgnoreCase(key)) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            PortSession session = getOrCreateSession(port, true);
            for (String raw : values) {
                if (raw == null) {
                    continue;
                }
                String[] parts = raw.split(";");
                if (parts.length == 0) {
                    continue;
                }
                String pair = parts[0].trim();
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String cName = pair.substring(0, eq).trim();
                String cVal = pair.substring(eq + 1).trim();
                if (cName.isEmpty()) {
                    continue;
                }

                boolean deleteCookie = false;
                long expiresAtMs = 0L;
                for (int i = 1; i < parts.length; i++) {
                    String attr = parts[i].trim();
                    int aEq = attr.indexOf('=');
                    if (aEq <= 0) continue;
                    String aKey = attr.substring(0, aEq).trim().toLowerCase(Locale.US);
                    String aVal = attr.substring(aEq + 1).trim();
                    if ("max-age".equals(aKey)) {
                        try {
                            long maxAgeSec = Long.parseLong(aVal);
                            if (maxAgeSec <= 0L) {
                                deleteCookie = true;
                            } else {
                                expiresAtMs = now + (maxAgeSec * 1000L);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("expires".equals(aKey) && aVal.contains("1970")) {
                        deleteCookie = true;
                    }
                }

                if (deleteCookie || cVal.isEmpty()) {
                    session.cookies.remove(cName);
                } else {
                    session.cookies.put(cName, new CookieEntry(cVal, expiresAtMs));
                }
            }
        }
    }

    private static String buildCookieHeader(int port, String incomingCookie) {
        PortSession session = getOrCreateSession(port, false);
        ConcurrentHashMap<String, CookieEntry> jar = session != null ? session.cookies : null;
        if ((jar == null || jar.isEmpty()) && (incomingCookie == null || incomingCookie.isEmpty())) {
            return null;
        }
        long now = System.currentTimeMillis();
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (jar != null) {
            for (Map.Entry<String, CookieEntry> e : jar.entrySet()) {
                CookieEntry ce = e.getValue();
                if (ce == null || ce.isExpired(now)) {
                    jar.remove(e.getKey(), ce);
                    continue;
                }
                merged.put(e.getKey(), ce.value);
            }
        }
        if (incomingCookie != null && !incomingCookie.trim().isEmpty()) {
            String[] pairs = incomingCookie.split(";");
            for (String p : pairs) {
                String trimmed = p.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    String k = trimmed.substring(0, eq).trim();
                    String v = trimmed.substring(eq + 1).trim();
                    if (!k.isEmpty()) {
                        merged.put(k, v);
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static String injectTokenBootstrapScript(String html, String token) {
        if (html == null || token == null || token.isEmpty() || html.contains("__agySidecarTok")) {
            return html;
        }
        String jsEscaped = token
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "")
                .replace("<", "\\u003c");
        String snippet = "<script>(function(){if(window.__agySidecarTok)return;"
                + "var T=\"" + jsEscaped + "\";window.__agySidecarTok=T;"
                + "function u(x){try{var p=new URL(String(x),location.href);"
                + "if(p.origin===location.origin&&!p.searchParams.has(\"token\"))p.searchParams.set(\"token\",T);"
                + "return p.toString()}catch(e){return x}}"
                + "var F=window.fetch;if(typeof F===\"function\"){window.fetch=function(r,i){"
                + "var nx=(typeof r===\"string\"||r instanceof URL)?u(r):r;var ni=i;"
                + "try{var h=new Headers((i&&i.headers)||(r&&r.headers)||void 0);"
                + "if(!h.has(\"x-sidecar-token\"))h.set(\"x-sidecar-token\",T);"
                + "ni=Object.assign({},i||{},{headers:h})}catch(e){}"
                + "return F.call(this,nx,ni)}}"
                + "var E=window.EventSource;if(typeof E===\"function\"){"
                + "var W=function(s,c){return new E(u(s),c)};W.prototype=E.prototype;window.EventSource=W}"
                + "var X=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,s){"
                + "var a=Array.prototype.slice.call(arguments);if(a.length>1)a[1]=u(s);"
                + "this.__agyTok=T;return X.apply(this,a)};"
                + "var S=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){"
                + "try{if(this.__agyTok)this.setRequestHeader(\"x-sidecar-token\",this.__agyTok)}catch(e){}"
                + "return S.apply(this,arguments)}})();</script>";
        int headIdx = html.toLowerCase(Locale.US).indexOf("<head>");
        if (headIdx >= 0) {
            int insertPos = headIdx + "<head>".length();
            return html.substring(0, insertPos) + snippet + html.substring(insertPos);
        }
        return snippet + html;
    }

    private static String parseMimeType(String contentType, String path) {
        if (contentType != null && !contentType.trim().isEmpty()) {
            int semi = contentType.indexOf(';');
            String m = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
            if (!m.isEmpty()) {
                return m;
            }
        }
        return MimeTypes.getMimeType(path);
    }

    private static String parseCharset(String contentType, String mimeType) {
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.toLowerCase(Locale.US).startsWith("charset=")) {
                    String cs = p.substring("charset=".length()).trim();
                    if (cs.startsWith("\"") && cs.endsWith("\"") && cs.length() >= 2) {
                        cs = cs.substring(1, cs.length() - 1);
                    }
                    if (!cs.isEmpty()) {
                        return cs;
                    }
                }
            }
        }
        return MimeTypes.isTextMime(mimeType) ? "UTF-8" : null;
    }

    private static Map<String, String> extractResponseHeaders(Map<String, List<String>> raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                String k = e.getKey();
                List<String> vals = e.getValue();
                if (k == null || vals == null || vals.isEmpty()) {
                    continue;
                }
                String lower = k.toLowerCase(Locale.US);
                if ("set-cookie".equals(lower) || "transfer-encoding".equals(lower)
                        || "connection".equals(lower) || "keep-alive".equals(lower)) {
                    continue;
                }
                out.put(k, vals.get(vals.size() - 1));
            }
        }
        if (!out.containsKey("Access-Control-Allow-Origin")) {
            out.put("Access-Control-Allow-Origin", "*");
        }
        return out;
    }

    private static String safeReason(String msg, int status) {
        if (msg != null) {
            String trimmed = msg.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default: return status >= 400 ? "Error" : "OK";
        }
    }

    private static byte[] readUpTo(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()))) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(HttpURLConnection conn) {
        if (conn == null) {
            return;
        }
        try {
            InputStream in = conn.getInputStream();
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
        try {
            InputStream err = conn.getErrorStream();
            if (err != null) err.close();
        } catch (Exception ignored) {
        }
        conn.disconnect();
    }
}
