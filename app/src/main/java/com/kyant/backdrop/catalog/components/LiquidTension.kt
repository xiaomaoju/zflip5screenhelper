package com.kyant.backdrop.catalog.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.isRuntimeShaderSupported

/** Immutable material inputs; defaults preserve the notification material. Geometry uses pixels. */
@Immutable
data class LiquidTensionStyle(
    val gain: Float = .74f,
    val edgeGain: Float = .90f,
    val tint: Float = .08f,
    val edgeTint: Float = .045f,
    val luminanceLimit: Float = .38f,
    val rimBase: Float = .035f,
    val rimStrength: Float = .22f,
    val shadowStrength: Float = .18f,
    val fallbackColor: Color = Color(.16f, .20f, .25f)
) {
    companion object {
        @JvmField val Default = LiquidTensionStyle()
        @JvmField val LauncherDock = LiquidTensionStyle(gain = .72f, tint = .025f, luminanceLimit = 1f)
    }
    internal fun apply(shader: RuntimeShader) {
        shader.setFloatUniform("tone", gain, edgeGain, tint, edgeTint)
        shader.setFloatUniform("lighting", luminanceLimit, rimBase, rimStrength, shadowStrength)
    }
}

/** Geometry in the shared surface's pixels; buttons keep their own input and accessibility. */
class LiquidTensionGeometry {
    companion object { const val MAX_SHAPES = 8; const val HIGHLIGHT_DURATION_MS = 300L }
    val shapes = FloatArray(MAX_SHAPES * 4)
    val radii = FloatArray(MAX_SHAPES)
    val joins = FloatArray(MAX_SHAPES)
    var count = 0
        private set
    var connectionRange = 48f
    var bevel = 10f
    var refraction = 8f
    var edgeFade = 0f
    var highlight = 1f
    private var pairFirst = -1
    private var pairSecond = -1
    private val orderedShapes = FloatArray(MAX_SHAPES * 4)
    private val orderedRadii = FloatArray(MAX_SHAPES)
    private val orderedJoins = FloatArray(MAX_SHAPES)

    fun reset() { count = 0; pairFirst = -1; pairSecond = -1 }
    fun add(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        require(count < MAX_SHAPES) { "Liquid tension supports at most $MAX_SHAPES surfaces" }
        if (right <= left || bottom <= top) return
        val index = count * 4
        shapes[index] = (left + right) * .5f
        shapes[index + 1] = (top + bottom) * .5f
        shapes[index + 2] = (right - left) * .5f
        shapes[index + 3] = (bottom - top) * .5f
        radii[count] = radius.coerceIn(0f, minOf(shapes[index + 2], shapes[index + 3]))
        joins[count] = connectionRange
        count++
    }

    /** A small liquid bud grows outward from its leading edge; the host's hit target stays fixed. */
    fun growCircleFromStart(index: Int, progress: Float, vertical: Boolean) {
        require(index in 1 until count)
        val scale = LiquidTensionMotion.circleScale(progress)
        val offset = index * 4
        val axis = if (vertical) 1 else 0
        shapes[offset + axis] -= shapes[offset + axis + 2] * (1f - scale)
        shapes[offset + 2] *= scale; shapes[offset + 3] *= scale
        radii[index] *= scale
    }

    /** Shape 0 is the moving body; remaining shapes run left to right. Rightmost splits first. */
    @JvmOverloads
    fun splitHorizontallyInOrder(separationMargin: Float, completionGap: Float = Float.POSITIVE_INFINITY) {
        splitInOrder(0, separationMargin, completionGap)
    }

    /** Shape 0 is the upper body; remaining shapes run top to bottom. Lowest splits first. */
    @JvmOverloads
    fun splitVerticallyInOrder(separationMargin: Float, completionGap: Float = Float.POSITIVE_INFINITY) {
        splitInOrder(1, separationMargin, completionGap)
    }

    private fun splitInOrder(axis: Int, separationMargin: Float, completionGap: Float) {
        pairFirst = -1; pairSecond = -1
        val margin = minOf(separationMargin, completionGap * .2f).coerceAtLeast(1f)
        // Calibrate against the actual settled gap, so the last button also finishes before rest.
        var releaseRange = minOf(connectionRange, 3f * (completionGap - 2f * margin)).coerceAtLeast(.01f)
        var shoulderRange = connectionRange
        // Leave room for a complete, antialiased break before the next button leaves the body.
        for (i in 2 until count) {
            val previous = (i - 1) * 4; val current = i * 4
            val gap = shapes[current + axis] - shapes[current + axis + 2] - shapes[previous + axis] - shapes[previous + axis + 2]
            releaseRange = minOf(releaseRange, (3f * (gap - 2f * margin)).coerceAtLeast(.01f))
            shoulderRange = minOf(shoulderRange, (3f * (gap - margin)).coerceAtLeast(.01f))
        }
        joins.fill(0f)
        val bodyEnd = shapes[axis] + shapes[axis + 2]
        for (i in count - 1 downTo 1) {
            val index = i * 4
            val gap = shapes[index + axis] - shapes[index + axis + 2] - bodyEnd
            if (gap <= releaseRange / 3f + margin) {
                // A broad shoulder while overlapping, then a progressively thinner neck.
                // Capping every pose by the tiny resting gap produced a visible corner.
                val width = minOf(shoulderRange, radii[i] * 1.5f).coerceAtLeast(releaseRange)
                val t = (-gap / maxOf(1f, radii[i] * .7f)).coerceIn(0f, 1f)
                val ease = t * t * t * (t * (t * 6f - 15f) + 10f)
                joins[i] = releaseRange + (width - releaseRange) * ease
                break
            }
        }
    }

    /** The host moves the last two surfaces together; taper the neck as they become one circle. */
    fun mergeLastPair(progress: Float, maximumRange: Float) {
        if (count >= 2) mergePair(count - 2, count - 1, progress, maximumRange)
    }

    /** Only this pair fuses; other registered surfaces remain independent. The host moves them. */
    fun mergePair(first: Int, second: Int, progress: Float, maximumRange: Float) {
        require(first in 0 until count && second in 0 until count && first != second)
        pairFirst = minOf(first, second); pairSecond = maxOf(first, second)
        val amount = progress.coerceIn(0f, 1f)
        val entrance = (amount / .3f).coerceIn(0f, 1f)
        joins.fill(0f)
        joins[pairSecond] = maximumRange.coerceAtLeast(0f) * entrance * entrance * (3f - 2f * entrance) * (1f - amount)
    }

    /** Conservative support, including every smooth-union expansion and antialiasing. */
    fun bounds(out: RectF) {
        out.setEmpty()
        var expansion = 1.7f
        for (i in 0 until count) {
            val index = i * 4
            out.union(shapes[index] - shapes[index + 2], shapes[index + 1] - shapes[index + 3], shapes[index] + shapes[index + 2], shapes[index + 1] + shapes[index + 3])
            if (i > 0) expansion += joins[i].coerceAtLeast(0f) / 6f
        }
        if (count > 0) out.inset(-expansion, -expansion)
    }

    internal fun apply(shader: RuntimeShader, originX: Float, originY: Float, transform: FloatArray) {
        shader.setIntUniform("count", count)
        if (pairFirst >= 0) {
            // AGSL requires statically indexed uniform arrays. Reorder once per draw,
            // keeping caller IDs stable and avoiding per-pixel pair selection.
            for (target in 0 until count) {
                var source = target
                if (target == 0) source = pairFirst
                else if (target == 1) source = pairSecond
                else { source = target - 2; if (source >= pairFirst) source++; if (source >= pairSecond) source++ }
                for (axis in 0..3) orderedShapes[target * 4 + axis] = shapes[source * 4 + axis]
                orderedRadii[target] = radii[source]; orderedJoins[target] = joins[source]
            }
        }
        shader.setFloatUniform("nodes", if (pairFirst >= 0) orderedShapes else shapes)
        shader.setFloatUniform("radii", if (pairFirst >= 0) orderedRadii else radii)
        shader.setFloatUniform("joins", if (pairFirst >= 0) orderedJoins else joins)
        shader.setFloatUniform("bevel", bevel.coerceAtLeast(1f))
        shader.setFloatUniform("refraction", refraction.coerceAtLeast(0f))
        shader.setFloatUniform("edgeFade", edgeFade.coerceAtLeast(0f))
        shader.setFloatUniform("highlight", highlight.coerceIn(0f, 1f))
        shader.setFloatUniform("origin", originX, originY)
        shader.setFloatUniform("transform", transform)
    }
}

/** Backdrop extension: fuse geometry before refraction, avoiding a blur pass over button content. */
fun BackdropEffectScope.liquidTension(geometry: LiquidTensionGeometry, style: LiquidTensionStyle = LiquidTensionStyle.Default) {
    val defaults = style == LiquidTensionStyle.Default
    runtimeShaderEffect(if (defaults) "LiquidTension" else "LiquidTensionStyled", if (defaults) LiquidTensionDefaultShader else LiquidTensionShader, "content") {
        geometry.apply(this, 0f, 0f, IdentityTransform)
        if (!defaults) style.apply(this)
    }
}

/** One material for a button group, or a card with buttons. Put independently clickable content above it. */
@Composable
fun LiquidTensionSurface(
    backdrop: Backdrop,
    geometry: () -> LiquidTensionGeometry,
    modifier: Modifier = Modifier,
    style: LiquidTensionStyle = LiquidTensionStyle.Default,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val surface = if (enabled && isRuntimeShaderSupported()) {
        // Observing geometry here also invalidates the host draw when an effect-only
        // input changes. Updating a cached RenderEffect alone need not repaint its host.
        modifier.drawBehind { geometry() }.drawBackdrop(backdrop, { RectangleShape }, { liquidTension(geometry(), style) }, highlight = null, shadow = null)
    } else modifier.drawBehind {
        val group = geometry()
        for (i in 0 until group.count) {
            val index = i * 4
            drawRoundRect(style.fallbackColor, Offset(group.shapes[index] - group.shapes[index + 2], group.shapes[index + 1] - group.shapes[index + 3]), Size(group.shapes[index + 2] * 2, group.shapes[index + 3] * 2), CornerRadius(group.radii[i]))
        }
    }
    Box(surface, content = content)
}

/** Native View adapter for the same library effect; borrows an existing texture and owns no bitmap. */
class LiquidTensionRenderer {
    private var shader: RuntimeShader? = null
    private var styledShader: RuntimeShader? = null
    private var plain: android.graphics.RuntimeShader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transform = FloatArray(4)
    private val bounds = RectF()

    /** May be called while the host is idle; draw still initializes lazily if necessary. */
    fun prepare() { if (Build.VERSION.SDK_INT >= 33 && shader == null) shader = RuntimeShader(LiquidTensionDefaultShader) }

    @JvmOverloads
    fun draw(canvas: Canvas, width: Int, height: Int, geometry: LiquidTensionGeometry, source: Shader?, matrix: FloatArray, style: LiquidTensionStyle = LiquidTensionStyle.Default): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated || geometry.count == 0) return false
        geometry.bounds(bounds)
        if (!bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return false
        prepare()
        val defaults = style == LiquidTensionStyle.Default
        val program = if (defaults) shader!! else styledShader ?: RuntimeShader(LiquidTensionShader).also { styledShader = it }
        val fallback = if (source == null) plain ?: android.graphics.RuntimeShader("uniform half4 color; half4 main(float2 p) { return color; }").also { plain = it } else null
        fallback?.setFloatUniform("color", style.fallbackColor.red, style.fallbackColor.green, style.fallbackColor.blue, 1f)
        program.asAndroidRuntimeShader().setInputShader("content", source ?: fallback!!)
        transform[0] = matrix[0]; transform[1] = matrix[1]; transform[2] = matrix[3]; transform[3] = matrix[4]
        geometry.apply(program, matrix[2], matrix[5], transform)
        if (!defaults) style.apply(program)
        paint.shader = program.asAndroidRuntimeShader()
        // Keep the original primitive: shrinking it changes device raster precision.
        // Bounds still cull wholly offscreen groups before shader preparation.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        return true
    }

    fun clear() { paint.shader = null; shader = null; styledShader = null; plain = null }
}

private val IdentityTransform = floatArrayOf(1f, 0f, 0f, 1f)

// Analytic rounded-rectangle SDF and C2 cubic smooth union; no SDF texture or per-frame bitmap.
// The Backdrop runtimeShaderEffect cache handles shader lifetime in Compose.
internal const val LiquidTensionShader = """
uniform shader content;
uniform int count;
uniform float4 nodes[8];
uniform float radii[8];
uniform float joins[8];
uniform float bevel;
uniform float refraction;
uniform float edgeFade;
uniform float highlight;
uniform float2 origin;
uniform float4 transform;
uniform float4 tone;
uniform float4 lighting;

// Distance, analytic gradient and local bevel are blended by the same smooth union.
// This gives the neck a continuous normal and shades the entire connected surface once.
float4 field(float2 p, out float2 opticalSlope) {
    float4 result = float4(100000.0, 0.0, 0.0, bevel);
    float3 lens = float3(100000.0, 0.0, 0.0);
    for (int i = 0; i < 8; i++) {
        if (i < count) {
            float2 center = p - nodes[i].xy;
            float2 q = abs(center) - nodes[i].zw + radii[i];
            float2 outside = max(q, 0.0);
            float magnitude = length(outside);
            float next = magnitude + min(max(q.x, q.y), 0.0) - radii[i];
            float2 normal = sign(center) * (magnitude > .0001 ? outside / magnitude : (q.x > q.y ? float2(1,0) : float2(0,1)));
            float localBevel = max(1.0, min(bevel, min(radii[i], nodes[i].w * .76)));
            float range = joins[i];
            if (range > 0.0) {
                // The optical surface keeps a broad shoulder as the silhouette pinches.
                // Bending two opposing samples across a subpixel neck creates a hard seam.
                float lensRange = max(range, bevel * 2.0);
                float lh = max(1.0 - abs(lens.x-next)/lensRange,0.0);
                float lw = .5 * lh * lh;
                float ld = min(lens.x,next) - lensRange * lh * lh * lh / 6.0;
                lens = mix(float3(next,normal),lens,lens.x < next ? 1.0-lw : lw);
                lens.x = ld;
            } else if (next < result.x) lens = float3(next,normal);
            float h = max(1.0 - abs(result.x - next) / max(range,.0001), 0.0);
            float weight = .5 * h * h;
            float previousWeight = result.x < next ? 1.0 - weight : weight;
            float distance = min(result.x,next) - range * h * h * h / 6.0;
            result = mix(float4(next, normal, localBevel), result, previousWeight);
            result.x = distance;
        }
    }
    opticalSlope = lens.yz;
    return result;
}
half4 main(float2 p) {
    float2 opticalSlope;
    float4 surface = field(p, opticalSlope);
    float d = surface.x;
    float coverage = 1.0 - smoothstep(-.7, .7, d);
    if (edgeFade > 0.0) coverage *= clamp(p.x / edgeFade, 0.0, 1.0);
    if (coverage <= 0.0) return half4(0.0);
    // Opposing slopes cancel smoothly at the saddle. Normalizing the vanishing
    // gradient amplifies it into a full-strength direction flip and a refraction seam.
    float2 normal = surface.yz;
    float edge = clamp(1.0 + d / surface.w, 0.0, 1.0);
    float curve = edge * edge * (3.0 - 2.0 * edge);
    float2 local = p + opticalSlope * curve * refraction * surface.w / bevel;
    float2 samplePoint = origin + float2(dot(transform.xy, local), dot(transform.zw, local));
    half4 sample = content.eval(samplePoint);
    half3 color = sample.rgb * mix(tone.x, tone.y, edge) + half3(mix(tone.z,tone.w,edge));
    float luminance = dot(color, half3(.2126,.7152,.0722));
    color *= mix(1.0, min(1.0, lighting.x / max(luminance,.001)), 1.0 - edge);
    float light = dot(normal, float2(-.60,-.80));
    float rim = smoothstep(.78,1.0,edge) * (lighting.y + lighting.z * pow(abs(light),4.0)) * highlight;
    color += (half3(.25) + sample.rgb * .75) * rim;
    color *= 1.0 - lighting.w * edge * (1.0 - edge);
    color -= edge * edge * .035 * max(-light,0.0);
    return half4(clamp(color,0.0,1.0),1) * coverage;
}
"""

// Keep the approved constant-folded material exactly; custom styles use the same
// algorithm with uniforms. Build both sources once, never rewrite source per frame.
private val LiquidTensionDefaultShader = LiquidTensionShader
    .replace("tone.x", ".74").replace("tone.y", ".90")
    .replace("tone.z", ".08").replace("tone.w", ".045")
    .replace("lighting.x", ".38").replace("lighting.y", ".035")
    .replace("lighting.z", ".22").replace("lighting.w", ".18")
