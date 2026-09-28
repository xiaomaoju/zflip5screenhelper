package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Conservative reader for AOSP shell tables. No credentials, commands or persistence. */
final class WifiNetworks {
    record Network(String name, boolean saved, boolean connected) { }
    static List<Network> read(String status, String saved, String scanned) {
        LinkedHashMap<String, Network> networks = new LinkedHashMap<>(); String current = "";
        for (String line : status.split("\\R")) if (line.startsWith("Wifi is connected to ")) {
            current = line.substring(21).trim(); if (current.startsWith("\"") && current.endsWith("\"") && current.length() > 1) current = current.substring(1, current.length() - 1);
            if (current.equals("<unknown ssid>")) current = "";
        }
        if (!current.isEmpty()) networks.put(current, new Network(current, false, true));
        if (saved.contains("Network Id") && saved.contains("SSID")) for (String line : saved.split("\\R")) {
            if (line.length() < 46 || !line.substring(0, 12).trim().matches("[0-9]+")) continue;
            String name = line.substring(13, 45).trim();
            if (!name.isEmpty()) networks.put(name, new Network(name, true, name.equals(current)));
            if (networks.size() >= 30) break;
        }
        if (scanned.contains("BSSID") && scanned.contains("SSID")) for (String line : scanned.split("\\R")) {
            if (line.length() < 89 || !line.substring(1, 18).matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) continue;
            String name = line.substring(56, 88).trim();
            if (!name.isEmpty()) networks.putIfAbsent(name, new Network(name, false, name.equals(current)));
            if (networks.size() >= 30) break;
        }
        return new ArrayList<>(networks.values());
    }
}
