package com.aid.director.controller;

/** 只接受回环反向代理覆写的真实地址，直连请求不能自报IP绕过限流。 */
public final class DirectorClientAddress {
    private DirectorClientAddress() { }

    public static String resolve(String remote, String forwarded) {
        if (remote == null) return "unknown";
        if (!("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote))) return remote;
        if (forwarded == null || forwarded.length() > 45 || !(forwarded.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
                || forwarded.contains(":") && forwarded.matches("[a-fA-F0-9:]+"))) return remote;
        try { return java.net.InetAddress.getByName(forwarded).getHostAddress(); }
        catch (java.net.UnknownHostException ignored) { return remote; }
    }
}
