package io.github.flipcover.controls;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded streaming copy with size/hash checks; never loads an APK into memory. */
final class UpdateTransfer {
    static byte[] read(InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > limit) throw new IOException("更新目录或配置文件过大"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }
    static void copy(InputStream input, OutputStream output, long expectedSize, String expectedHash, BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; long size = 0; int lastPercent = -1;
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消下载");
            int count = input.read(buffer); if (count == -1) break;
            size += count; if (size > expectedSize) throw new IOException("APK 大小超过更新目录的声明");
            output.write(buffer, 0, count); digest.update(buffer, 0, count);
            int percent = (int) (size * 100 / expectedSize); if (percent != lastPercent) { lastPercent = percent; progress.accept(size); }
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消下载");
        if (size != expectedSize) throw new IOException("APK 下载不完整，请重试");
        StringBuilder hash = new StringBuilder(64); for (byte value : digest.digest()) hash.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        if (!hash.toString().equals(expectedHash)) throw new IOException("APK 校验失败，请重新下载");
    }
}
