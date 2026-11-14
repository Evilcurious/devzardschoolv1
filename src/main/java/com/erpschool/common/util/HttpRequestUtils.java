package com.erpschool.common.util;

import jakarta.servlet.http.HttpServletRequest;

public final class HttpRequestUtils {

    private HttpRequestUtils() {
    }

    /** Client address. Forwarding headers are honoured only when the request came through a trusted proxy. */
    public static String clientIp(HttpServletRequest request) {
        return com.erpschool.saas.support.ClientIp.of(request);
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null) {
            return null;
        }
        return ua.length() > 300 ? ua.substring(0, 300) : ua;
    }
}
