package io.github.flipcover.controls;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class UpdateTransferTest {
    private static final byte[] CONTENT = "hello".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    @Test public void copiesAndVerifiesExactContent() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); AtomicInteger progress = new AtomicInteger();
        UpdateTransfer.copy(new ByteArrayInputStream(CONTENT), output, CONTENT.length, HASH, () -> false, size -> progress.set((int) size));
        assertArrayEquals(CONTENT, output.toByteArray()); assertEquals(CONTENT.length, progress.get());
    }
    @Test public void rejectsCorruptContent() { assertThrows(IOException.class, () -> UpdateTransfer.copy(new ByteArrayInputStream("jello".getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(), 5, HASH, () -> false, size -> { })); }
    @Test public void rejectsTruncatedContent() { assertThrows(IOException.class, () -> UpdateTransfer.copy(new ByteArrayInputStream(CONTENT), new ByteArrayOutputStream(), 6, HASH, () -> false, size -> { })); }
    @Test public void rejectsOversizeBeforeWritingIt() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> UpdateTransfer.copy(new ByteArrayInputStream(CONTENT), output, 4, HASH, () -> false, size -> { })); assertEquals(0, output.size());
    }
    @Test public void cancellationStopsBeforeReading() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(InterruptedIOException.class, () -> UpdateTransfer.copy(new ByteArrayInputStream(CONTENT), output, 5, HASH, () -> true, size -> { })); assertEquals(0, output.size());
    }
    @Test public void cancellationAtEndCannotReportVerified() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedIOException.class, () -> UpdateTransfer.copy(new ByteArrayInputStream(CONTENT), new ByteArrayOutputStream(), 5, HASH, () -> checks.incrementAndGet() >= 3, size -> { }));
    }
    @Test public void boundedReadAllowsExactLimit() throws Exception { assertArrayEquals(CONTENT, UpdateTransfer.read(new ByteArrayInputStream(CONTENT), 5)); }
    @Test public void boundedReadRejectsLargerPayload() { assertThrows(IOException.class, () -> UpdateTransfer.read(new ByteArrayInputStream(CONTENT), 4)); }
}
