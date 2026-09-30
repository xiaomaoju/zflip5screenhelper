package io.github.flipcover.controls;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;

/** GPU preparation, once per opening. Output bitmaps own their buffer references. */
@android.annotation.TargetApi(31)
final class GlassBackdrop implements AutoCloseable {
    final Bitmap sharp, soft, strong;
    final int width, height;
    final long bytes;
    GlassBackdrop(Bitmap sharp, Bitmap soft, Bitmap strong, int width, int height) {
        this.sharp = sharp; this.soft = soft; this.strong = strong; this.width = width; this.height = height;
        bytes = bytes(sharp) + bytes(soft) + bytes(strong);
    }
    private static long bytes(Bitmap bitmap) { return (long) bitmap.getWidth() * bitmap.getHeight() * 4; }
    /** Takes ownership of source even if preparation fails. */
    static GlassBackdrop prepare(Bitmap source, float density, int blurLimit) {
        return prepare(source,density,blurLimit,source.getWidth(),source.getHeight());
    }
    /** Output geometry and blur radii use display pixels, independent of app surface scaling. */
    static GlassBackdrop prepare(Bitmap source, float density, int blurLimit, int width, int height) {
        Bitmap sharp = null, soft = null, strong = null;
        try {
            float scale = Math.min(1, 1024f / Math.max(width, height));
            int sharpWidth=Math.max(1,Math.round(width*scale)), sharpHeight=Math.max(1,Math.round(height*scale));
            sharp = source.getWidth()==sharpWidth && source.getHeight()==sharpHeight ? source : render(source, sharpWidth, sharpHeight, 0);
            float blurredScale = Math.min(1, blurLimit / (float) Math.max(width, height));
            int w = Math.max(1, Math.round(width * blurredScale)), h = Math.max(1, Math.round(height * blurredScale));
            soft = render(sharp, w, h, 18 * density * blurredScale);
            strong = render(sharp, w, h, 28 * density * blurredScale);
            GlassBackdrop backdrop = new GlassBackdrop(sharp, soft, strong, width, height);
            if (backdrop.bytes > 12L * 1024 * 1024) throw new IllegalStateException("glass texture budget");
            return backdrop;
        } catch (RuntimeException | OutOfMemoryError failure) {
            if (sharp != null && sharp != source) sharp.recycle();
            if (soft != null) soft.recycle(); if (strong != null) strong.recycle(); source.recycle(); throw failure;
        } finally { if (sharp != source && !source.isRecycled()) source.recycle(); }
    }
    private static Bitmap render(Bitmap source, int width, int height, float radius) {
        RenderNode node = new RenderNode("Panel glass preparation"); node.setPosition(0,0,width,height);
        if (radius > 0) node.setRenderEffect(RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP));
        Canvas canvas = node.beginRecording(); canvas.drawBitmap(source,null,new Rect(0,0,width,height),new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG)); node.endRecording();
        return renderRecorded(node);
    }
    /** UI-thread recording only; GPU submission and buffer waiting run on the worker. */
    static RenderNode recordLocal(android.view.View view) {
        int width=view.getWidth(), height=view.getHeight();
        if (width <= 0 || height <= 0) throw new IllegalStateException("unmeasured glass underlay");
        RenderNode node=new RenderNode("Panel glass underlay"); node.setPosition(0,0,width,height);
        Canvas canvas=node.beginRecording(); view.draw(canvas); node.endRecording(); return node;
    }
    /** Consumes the display list even when the renderer fails. */
    static Bitmap renderRecorded(RenderNode node) {
        ImageReader reader=null;
        HardwareRenderer renderer=null;
        try {
            reader=ImageReader.newInstance(node.getWidth(),node.getHeight(),PixelFormat.RGBA_8888,1,HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            renderer=new HardwareRenderer();
            renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (Image image=reader.acquireNextImage()) {
                if (image == null) throw new IllegalStateException("glass image unavailable");
                try (HardwareBuffer buffer=image.getHardwareBuffer()) {
                    if (buffer == null) throw new IllegalStateException("glass buffer unavailable");
                    Bitmap bitmap=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB));
                    if (bitmap == null) throw new IllegalStateException("glass bitmap unavailable");
                    return bitmap;
                }
            }
        } finally { if (renderer != null) renderer.destroy(); node.discardDisplayList(); if (reader != null) reader.close(); }
    }
    @Override public void close() { sharp.recycle(); soft.recycle(); strong.recycle(); }
}
