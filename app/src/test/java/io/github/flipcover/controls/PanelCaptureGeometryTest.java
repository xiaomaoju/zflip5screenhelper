package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelCaptureGeometryTest {
    @Test public void acceptsNativeAndUniformlyScaledWindowFramesInBothOrientations() {
        for (int[] size : new int[][]{{748,720},{720,748}}) {
            int width=size[0], height=size[1];
            assertTrue(PanelGlassSession.captureSizeMatches(width,height,width,height,false));
            assertTrue(PanelGlassSession.captureSizeMatches(width*2,height*2,width,height,true));
            assertTrue(PanelGlassSession.captureSizeMatches(width/2,height/2,width,height,true));
            assertTrue(PanelGlassSession.captureSizeMatches(Math.round(width*1.25f),Math.round(height*1.25f),width,height,true));
        }
    }
    @Test public void rejectsCroppedRotatedAndDisplayWideMismatches() {
        assertFalse(PanelGlassSession.captureSizeMatches(1496,1440,748,720,false));
        assertFalse(PanelGlassSession.captureSizeMatches(748,654,748,720,true));
        assertFalse(PanelGlassSession.captureSizeMatches(1496,1308,748,720,true));
        assertFalse(PanelGlassSession.captureSizeMatches(720,748,748,720,true));
        assertFalse(PanelGlassSession.captureSizeMatches(0,720,748,720,true));
        assertFalse(PanelGlassSession.captureSizeMatches(748,720,0,720,true));
    }
}
