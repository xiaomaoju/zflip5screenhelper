package io.github.flipcover.controls;

import java.nio.charset.StandardCharsets;

/** Shared validation and display rules; never persists hotspot credentials. */
final class HotspotConfiguration {
    static int nfcState(int value) { return value == 1 ? 0 : value == 3 ? 1 : -1; }
    static int hotspotState(int value) { return value == 11 ? 0 : value == 13 ? 1 : -1; }
    static boolean editablePassword(int security) { return security == 1 || security == 2 || security == 3; }
    static void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > 32 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("热点名称需为 1–32 字节，不能包含控制字符");
    }
    static void validatePassword(String value, int security) {
        if (!editablePassword(security)) throw new IllegalArgumentException("此安全类型的密码请在系统设置中修改");
        if (value == null || !value.matches("[\\x20-\\x7e]{8,63}")) throw new IllegalArgumentException("密码需为 8–63 位英文字母、数字或符号");
    }
    static long timeoutValue(boolean automatic, long platformValue) { return !automatic ? -1 : Math.max(0, platformValue); }
    static boolean validTimeout(long value) { return value == -1 || value == 0 || value == 300000 || value == 600000 || value == 1800000; }
    static String securityName(int security) {
        return switch (security) { case 0 -> "开放网络"; case 1 -> "WPA2"; case 2 -> "WPA2/WPA3"; case 3 -> "WPA3"; case 4, 5 -> "增强型开放网络"; default -> "系统安全类型"; };
    }
    static String bandName(int band) { return switch (band) { case 1 -> "2.4 GHz"; case 2 -> "5 GHz"; case 3 -> "2.4 / 5 GHz"; case 4 -> "6 GHz"; default -> "系统配置"; }; }
    static String timeoutName(long value) { return value < 0 ? "不自动关闭" : value == 0 ? "系统默认" : value / 60000 + " 分钟"; }
    static String qrPayload(String name, String password, int security, boolean hidden) {
        validateName(name);
        // SAE-only / OWE compatibility differs between scanners. Do not advertise an invalid WPA QR.
        if (security != 0 && security != 1 && security != 2) throw new IllegalArgumentException("此安全类型请使用系统热点分享");
        if (security != 0) validatePassword(password, security);
        return "WIFI:T:" + (security == 0 ? "nopass" : "WPA") + ";S:" + escape(name) + ";P:" + escape(security == 0 ? "" : password) + ";H:" + hidden + ";;";
    }
    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) { if ("\\;,:\"".indexOf(c) >= 0) result.append('\\'); result.append(c); }
        return result.toString();
    }
}
