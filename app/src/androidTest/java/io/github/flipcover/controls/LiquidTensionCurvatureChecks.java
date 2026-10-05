package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RuntimeShader;
import android.os.SystemClock;
import android.view.View;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import com.kyant.backdrop.catalog.components.LiquidTensionRenderer;
import java.io.File;
import java.io.FileOutputStream;

/** Sample real shader pixels through the liquid neck, including a high-contrast backdrop. */
final class LiquidTensionCurvatureChecks {
    private final Instrumentation test;
    LiquidTensionCurvatureChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Probe[] probe = {null}; StringBuilder report = new StringBuilder(); int worst = 0;
        try {
            test.runOnMainSync(() -> { probe[0] = new Probe(activity); activity.setContentView(probe[0]); });
            test.waitForIdleSync(); SystemClock.sleep(200);
            for (boolean detailed : new boolean[]{false, true}) for (int gap : new int[]{-12, -8, -4, 0, 1}) {
                test.runOnMainSync(() -> { probe[0].gap = gap; probe[0].detailed = detailed; probe[0].invalidate(); });
                test.waitForIdleSync(); SystemClock.sleep(100);
                Bitmap bitmap = test.getUiAutomation().takeScreenshot();
                int[] position = new int[2]; test.runOnMainSync(() -> probe[0].getLocationOnScreen(position));
                int cx = Math.round(position[0] + probe[0].neck), cy = Math.round(position[1] + probe[0].center);
                int jump = 0;
                for (int y = cy - 1; y <= cy + 1; y++) for (int x = cx - Ui.dp(activity,3); x < cx + Ui.dp(activity,3); x++) {
                    int a = bitmap.getPixel(x,y), b = bitmap.getPixel(x+1,y);
                    jump = Math.max(jump, Math.max(Math.abs(Color.red(a)-Color.red(b)), Math.max(Math.abs(Color.green(a)-Color.green(b)),Math.abs(Color.blue(a)-Color.blue(b)))));
                }
                worst = Math.max(worst, jump); report.append(detailed ? "contrast" : "flat").append(" gap=").append(gap).append("dp maxAdjacentChannelStep=").append(jump).append('\n');
                File directory = new File(activity.getFilesDir(),"liquid-tension-curvature"); directory.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(directory,(detailed ? "contrast-" : "flat-")+gap+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); }
                bitmap.recycle();
            }
            if (worst > 18) throw new AssertionError("Visible shading discontinuity through the neck: " + report);
            return "PASS: 10 native neck samples; maximum adjacent color step=" + worst + "/255; " + report;
        } finally { test.runOnMainSync(() -> { if (probe[0] != null) probe[0].renderer.clear(); activity.finish(); }); }
    }
    private static final class Probe extends View {
        final LiquidTensionGeometry geometry = new LiquidTensionGeometry();
        final LiquidTensionRenderer renderer = new LiquidTensionRenderer();
        final float[] identity = {1,0,0,0,1,0,0,0,1};
        final RuntimeShader flat = new RuntimeShader("half4 main(float2 p) { return half4(.13,.15,.18,1); }");
        final RuntimeShader contrast = new RuntimeShader("uniform float neck; uniform float width; half4 main(float2 p) { float t=smoothstep(-1.0,1.0,(p.x-neck)/width); return half4(mix(half3(.06,.07,.09),half3(.82,.52,.58),t),1); }");
        float gap, neck, center; boolean detailed;
        Probe(Activity activity) { super(activity); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xFF111820); float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() - 70*density, radius = 14.4f*density, right = cx-radius-gap*density;
            center = 130*density; neck = (right + cx-radius)*.5f;
            geometry.reset(); geometry.setConnectionRange(48*density); geometry.setBevel(22*density); geometry.setRefraction(18.7f*density);
            geometry.add(-30*density,center-27*density,right,center+27*density,26*density);
            geometry.add(cx-radius,center-radius,cx+radius,center+radius,radius);
            geometry.add(cx+36*density-radius,center-radius,cx+36*density+radius,center+radius,radius);
            geometry.splitHorizontallyInOrder(2*density,3.6f*density);
            contrast.setFloatUniform("neck",neck); contrast.setFloatUniform("width",12*density);
            renderer.draw(canvas,getWidth(),getHeight(),geometry,detailed ? contrast : flat,identity);
        }
    }
}
