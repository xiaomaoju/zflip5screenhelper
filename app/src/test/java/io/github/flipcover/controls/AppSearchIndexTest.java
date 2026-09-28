package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppSearchIndexTest {
    @Test public void localSearchRetainsChinesePinyinAndLatinNames() {
        assertEquals("weixin wx", AppSearchIndex.romanize("微信"));
        AppSearchIndex index = new AppSearchIndex();
        AppCatalogCache.Entry entry = new AppCatalogCache.Entry("app:test/.Main", "微信", "test", "微信 test");
        assertTrue(index.matches(entry, "聊天", "wx"));
        assertTrue(index.matches(entry, "聊天", "liaotian"));
        assertFalse(index.matches(entry, "聊天", "unrelated"));
        assertEquals("dock dock", AppSearchIndex.romanize("Dock"));
    }
}
