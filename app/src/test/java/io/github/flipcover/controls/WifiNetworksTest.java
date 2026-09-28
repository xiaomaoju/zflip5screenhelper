package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class WifiNetworksTest {
    @Test public void namesWithSpacesMergeAndConnectionIsNotInferredFromSaving() {
        String saved = "Network Id   SSID                             Security type\n" + String.format("%-12d %-32s %-4s\n", 4, "My Home WiFi", "WPA2") + String.format("%-12d %-32s %-4s\n", 5, "Guest", "OPEN");
        String scan = "BSSID Frequency RSSI Age(sec) SSID Flags\n" + String.format(" %17s %9d %18s %7s %-32s %s\n", "aa:bb:cc:dd:ee:ff", 2412, "-55", "1.000", "My Home WiFi", "[ESS]") + String.format(" %17s %9d %18s %7s %-32s %s\n", "11:22:33:44:55:66", 2412, "-65", "1.000", "附近网络", "[ESS]");
        var rows = WifiNetworks.read("Wifi is connected to \"My Home WiFi\"\n", saved, scan);
        assertEquals(3, rows.size()); assertEquals("My Home WiFi", rows.get(0).name()); assertTrue(rows.get(0).connected()); assertTrue(rows.get(0).saved()); assertFalse(rows.get(1).connected()); assertFalse(rows.get(2).saved());
        assertTrue(WifiNetworks.read("Wifi is not connected", saved, scan).stream().noneMatch(WifiNetworks.Network::connected));
    }
    @Test public void errorsAndUnknownFormatsNeverBecomeNetworks() {
        assertTrue(WifiNetworks.read("Wifi is connected to <unknown ssid>", "Error: access denied", "SecurityException").isEmpty());
        assertTrue(WifiNetworks.read("", "Network Id SSID\nnot a valid network row", "BSSID SSID\nmalformed").isEmpty());
    }
    @Test public void longListsAreBounded() {
        StringBuilder saved = new StringBuilder("Network Id SSID\n"); for (int i = 0; i < 200; i++) saved.append(String.format("%-12d %-32s %-4s\n", i, "network " + i, "WPA2"));
        assertEquals(30, WifiNetworks.read("", saved.toString(), "").size());
    }
}
