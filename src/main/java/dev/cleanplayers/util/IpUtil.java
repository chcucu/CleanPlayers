package dev.cleanplayers.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

public final class IpUtil {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    private static final Pattern NUMERIC_HOST = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private IpUtil() {
    }

    public static boolean isIp(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !NUMERIC_HOST.matcher(trimmed).matches()) {
            return false;
        }
        if (IPV4.matcher(trimmed).matches()) {
            String[] parts = trimmed.split("\\.");
            for (String part : parts) {
                try {
                    int octet = Integer.parseInt(part);
                    if (octet < 0 || octet > 255) {
                        return false;
                    }
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            return true;
        }
        return trimmed.contains(":") && canResolve(trimmed);
    }

    /**
     * Canonical, lower-case representation of an IP address, or {@code null} when the input
     * is not a numeric IP address. Hostname lookups are never performed.
     */
    public static String normalize(String value) {
        if (!isIp(value)) {
            return null;
        }
        String trimmed = value.trim().toLowerCase();
        try {
            return InetAddress.getByName(trimmed).getHostAddress().toLowerCase();
        } catch (UnknownHostException ex) {
            return trimmed;
        }
    }

    public static boolean isIpProtected(Collection<String> candidateIps, Set<String> protectedIps) {
        if (protectedIps.isEmpty() || candidateIps.isEmpty()) {
            return false;
        }
        for (String ip : candidateIps) {
            String normalized = normalize(ip);
            if (normalized != null && protectedIps.contains(normalized)) {
                return true;
            }
            if (protectedIps.contains(ip.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean canResolve(String value) {
        try {
            InetAddress.getByName(value);
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
