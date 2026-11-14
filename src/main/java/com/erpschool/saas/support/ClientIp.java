package com.erpschool.saas.support;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

/**
 * Client address for rate limiting and audit.
 *
 * <p>Forwarding headers are only believed when the TCP peer is the local
 * reverse proxy (loopback or a private address), which is how the app runs
 * behind Nginx and the Cloudflare Tunnel. A direct connection from the
 * internet cannot spoof its address with a header.</p>
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            String cf = request.getHeader("CF-Connecting-IP");
            if (cf != null && !cf.isBlank()) {
                return clean(cf);
            }
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return clean(forwarded.split(",")[0]);
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) {
                return clean(real);
            }
        }
        return remote == null ? "unknown" : remote;
    }

    private static String clean(String raw) {
        String v = raw.trim();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }

    static boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(remote);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
