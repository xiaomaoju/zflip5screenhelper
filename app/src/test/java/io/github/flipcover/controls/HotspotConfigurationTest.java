package io.github.flipcover.controls;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class HotspotConfigurationTest {
    @Test public void transitionalOrFailedRadioStateIsNeverReportedAsOff() {
        assertEquals(0, HotspotConfiguration.nfcState(1)); assertEquals(1, HotspotConfiguration.nfcState(3));
        for (int state : new int[]{-1, 0, 2, 4, 99}) assertEquals(-1, HotspotConfiguration.nfcState(state));
        assertEquals(0, HotspotConfiguration.hotspotState(11)); assertEquals(1, HotspotConfiguration.hotspotState(13));
        for (int state : new int[]{-1, 10, 12, 14, 99}) assertEquals(-1, HotspotConfiguration.hotspotState(state));
    }
    @Test public void ssidLimitCountsUtf8BytesAndRejectsControlCharacters() {
        HotspotConfiguration.validateName("外".repeat(10) + "ab");
        assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.validateName("外".repeat(11)));
        assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.validateName("bad\nname"));
        assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.validateName("  "));
        HotspotConfiguration.validateName("desk;room:1\\name");
    }
    @Test public void preservesSecurityTypeAndOnlyOffersSupportedQrFormats() {
        for (int security : new int[]{1, 2, 3}) { HotspotConfiguration.validatePassword("valid-demo", security); assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.validatePassword("short", security)); }
        for (int security : new int[]{0, 4, 5, -1}) assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.validatePassword("valid-demo", security));
        assertThrows(IllegalArgumentException.class, () -> HotspotConfiguration.qrPayload("SSID", "valid-demo", 3, false));
        assertEquals("WIFI:T:nopass;S:SSID;P:;H:false;;", HotspotConfiguration.qrPayload("SSID", "", 0, false));
    }
    @Test public void qrCanBeDecodedWithUnicodeAndEscapedPunctuation() throws Exception {
        String payload = HotspotConfiguration.qrPayload("外屏;desk:1", "demo;12:34\\56", 1, true);
        assertTrue(payload.contains("S:外屏\\;desk\\:1;")); assertTrue(payload.contains("P:demo\\;12\\:34\\\\56;"));
        BitMatrix image = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 192, 192, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
        int[] pixels = new int[192 * 192]; for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) pixels[y * 192 + x] = image.get(x, y) ? 0xff000000 : 0xffffffff;
        assertEquals(payload, new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(192, 192, pixels)))).getText());
    }
    @Test public void systemDefaultTimeoutDoesNotMeanAutoShutdownIsDisabled() {
        assertEquals(0, HotspotConfiguration.timeoutValue(true, -1)); assertEquals(0, HotspotConfiguration.timeoutValue(true, 0)); assertEquals(600000, HotspotConfiguration.timeoutValue(true, 600000)); assertEquals(-1, HotspotConfiguration.timeoutValue(false, 600000));
    }
    @Test public void autoShutdownChoicesAreBounded() {
        for (long value : new long[]{-1, 0, 300000, 600000, 1800000}) assertTrue(HotspotConfiguration.validTimeout(value));
        for (long value : new long[]{-2, 1, 1000, Long.MAX_VALUE}) assertFalse(HotspotConfiguration.validTimeout(value));
    }
}
