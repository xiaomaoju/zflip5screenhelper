package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherWidgetNavigationTest {
    @Test public void folderReturnRestoresItsOriginAndRepeatedBackDoesNotResetThePage() {
        var state = new LauncherWidgetBridge.State(); state.page = 3; state.openFolder("folder:first");
        assertEquals(0, state.page); assertEquals("folder:first", state.folder);
        assertTrue(state.closeFolder()); assertNull(state.folder); assertEquals(3, state.page);
        assertFalse(state.closeFolder()); assertEquals(3, state.page);
    }
    @Test public void repeatedFolderClickKeepsTheOriginalWorkspacePage() {
        var state = new LauncherWidgetBridge.State(); state.page = 2; state.openFolder("folder:first"); state.openFolder("folder:first");
        state.closeFolder(); assertEquals(2, state.page);
        state.page = 1; state.openFolder("folder:second"); state.closeFolder(); assertEquals(1, state.page);
    }
}
