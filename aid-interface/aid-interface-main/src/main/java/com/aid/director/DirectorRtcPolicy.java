package com.aid.director;

import com.alibaba.fastjson2.JSONObject;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** 局域网信令候选与媒体范围校验。 */
public final class DirectorRtcPolicy {
    private DirectorRtcPolicy() { }

    public static void candidate(JSONObject payload) {
        if (!Set.of("candidate", "sdpMid", "sdpMLineIndex", "usernameFragment").containsAll(payload.keySet())) throw DirectorException.invalid();
        String candidate = payload.getString("candidate");
        if (candidate == null || candidate.length() > 2048) reject();
        checkCandidate(candidate);
        String mid = payload.getString("sdpMid");
        if (mid == null || !mid.matches("[A-Za-z0-9_-]{1,32}")) reject();
        Integer index = payload.getInteger("sdpMLineIndex");
        if (index == null || index < 0 || index > 3) reject();
        String fragment = payload.getString("usernameFragment");
        if (fragment != null && !fragment.matches("[A-Za-z0-9+/]{1,256}")) reject();
    }

    public static void sdp(JSONObject payload, boolean offer) {
        if (!Set.of("type", "sdp").equals(payload.keySet())
                || !(offer ? "offer" : "answer").equals(payload.getString("type"))) reject();
        String sdp = payload.getString("sdp");
        if (sdp == null || sdp.getBytes(StandardCharsets.UTF_8).length > 65536 || sdp.indexOf('\0') >= 0) reject();
        int video = 0, application = 0, candidates = 0;
        boolean fingerprint = false, ufrag = false, pwd = false;
        String media = null;
        boolean videoDirection = false;
        for (String line : sdp.split("\\r?\\n")) {
            if (line.length() > 4096) reject();
            if (line.startsWith("m=video ")) {
                if (!line.matches("m=video [1-9][0-9]{0,4} UDP/TLS/RTP/SAVPF [0-9 ]+")) reject();
                video++; media = "video";
            }
            else if (line.startsWith("m=application ")) {
                if (!line.matches("m=application [1-9][0-9]{0,4} UDP/DTLS/SCTP webrtc-datachannel")) reject();
                application++; media = "application";
            }
            else if (line.startsWith("m=")) reject();
            if (line.startsWith("c=")) {
                String[] connection = line.split(" ");
                if (connection.length != 3 || !Set.of("c=IN").contains(connection[0])
                        || !Set.of("IP4", "IP6").contains(connection[1])
                        || !(Set.of("0.0.0.0", "::").contains(connection[2]) || localAddress(connection[2]))) reject();
            }
            if ("video".equals(media) && Set.of("a=sendonly", "a=recvonly", "a=sendrecv", "a=inactive").contains(line)) {
                if (!(offer ? "a=sendonly" : "a=recvonly").equals(line)) reject();
                videoDirection = true;
            }
            if (line.startsWith("a=fingerprint:")) {
                if (!line.matches("a=fingerprint:sha-256 (?:[A-Fa-f0-9]{2}:){31}[A-Fa-f0-9]{2}")) reject();
                fingerprint = true;
            }
            if (line.startsWith("a=ice-ufrag:")) { if (!line.substring(12).matches("[A-Za-z0-9+/]{1,256}")) reject(); ufrag = true; }
            if (line.startsWith("a=ice-pwd:")) { if (!line.substring(10).matches("[A-Za-z0-9+/]{22,256}")) reject(); pwd = true; }
            if (line.startsWith("a=candidate:")) { checkCandidate(line.substring(2)); if (++candidates > 64) reject(); }
        }
        if (video != 1 || application != 1 || !fingerprint || !ufrag || !pwd || !videoDirection) reject();
    }

    public static void checkCandidate(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 8 || !parts[0].matches("candidate:[A-Za-z0-9+/]{1,64}")
                || !Set.of("1", "2").contains(parts[1]) || !Set.of("udp", "tcp").contains(parts[2].toLowerCase(Locale.ROOT))
                || !"typ".equals(parts[6]) || !"host".equals(parts[7])) reject();
        try {
            int port = Integer.parseInt(parts[5]); long priority = Long.parseLong(parts[3]);
            if (port < 1 || port > 65535 || priority < 1 || priority > 0xffffffffL) reject();
        }
        catch (NumberFormatException e) { reject(); }
        if (!localAddress(parts[4])) reject();
    }

    public static boolean localAddress(String address) {
        if (address == null || address.length() > 253) return false;
        if (address.toLowerCase(Locale.ROOT).matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+local")) return true;
        if (address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] octets = address.split("\\."); int[] p = new int[4];
            for (int i = 0; i < 4; i++) { p[i] = Integer.parseInt(octets[i]); if (p[i] > 255) return false; }
            return p[0] == 10 || p[0] == 172 && p[1] >= 16 && p[1] <= 31
                    || p[0] == 192 && p[1] == 168 || p[0] == 169 && p[1] == 254;
        }
        if (address.indexOf(':') >= 0 && address.matches("[A-Fa-f0-9:]+")) {
            try {
                byte[] b = InetAddress.getByName(address).getAddress();
                return b.length == 16 && ((b[0] & 0xfe) == 0xfc || (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80);
            } catch (java.net.UnknownHostException ignored) { return false; }
        }
        return false;
    }

    private static void reject() { throw new DirectorException("NETWORK_POLICY_REJECTED", 409, "直连参数不符合要求"); }
}
