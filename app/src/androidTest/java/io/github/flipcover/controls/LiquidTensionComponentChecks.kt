package io.github.flipcover.controls

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry
import com.kyant.backdrop.catalog.components.LiquidTensionStyle
import com.kyant.backdrop.catalog.components.LiquidTensionSurface
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** A real Compose host, with two buttons and no notification/card dependency. */
class LiquidTensionComponentChecks(private val test: Instrumentation) {
    private var assertions = 0
    private lateinit var host: Host
    private fun require(value: Boolean, message: String) { if (!value) throw AssertionError(message); assertions++ }
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun idle() { test.waitForIdleSync(); SystemClock.sleep(160); test.waitForIdleSync() }
    fun run(): String {
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { host = Host(activity); activity.setContentView(host) }; idle()
            val separated = capture(activity, "separated")
            val positions = IntArray(2); main { host.getLocationOnScreen(positions) }
            val density = activity.resources.displayMetrics.density
            fun pixel(image: Bitmap, x: Float, y: Float) = image.getPixel(positions[0] + (x*density).toInt(), positions[1] + (y*density).toInt())
            val background = pixel(separated, 118f, 100f)
            require(background == pixel(separated, 20f, 100f), "two standalone buttons have an empty gap")
            main { host.progress = .5f }; idle()
            val merged = capture(activity, "merged")
            require(pixel(merged,118f,100f) != background, "Compose observes geometry state and fuses only two buttons")
            main { host.extra = true }; idle()
            val triplet = capture(activity,"selected-pair")
            require(pixel(triplet,118f,100f) == pixel(merged,118f,100f) && pixel(triplet,220f,100f) != background, "nonadjacent registered pair fuses while the other surface stays independent")
            main { host.extra = false }
            main { host.style = LiquidTensionStyle.Default.copy(gain=.25f, edgeGain=.25f, tint=.01f, edgeTint=.01f) }; idle()
            val styled = capture(activity,"styled")
            require(pixel(styled,118f,100f) != pixel(merged,118f,100f), "material style changes without changing the geometry")
            main { host.progress=0f; host.effectEnabled=false }; idle()
            val fallback = capture(activity,"fallback")
            require(pixel(fallback,118f,100f) == background && pixel(fallback,100f,100f) != background, "disabled/unsupported branch draws individual shapes instead of a full rectangular plate")
            main {
                val start = SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(start,start+action*20L,action,36*density,36*density,0)
                    host.dispatchTouchEvent(event); event.recycle()
                }
            }; idle()
            require(host.clicks == 1, "fallback preserves independently clickable content")
            main { host.effectEnabled=true; host.style=LiquidTensionStyle.Default; host.progress=.5f; host.compose.layoutParams.width=(180*density).toInt(); host.compose.requestLayout() }; idle()
            val resized = capture(activity,"resized")
            require(pixel(resized,118f,100f) != background, "resizing and re-enabling retain live geometry")
            val bounds = RectF(); host.geometry.bounds(bounds)
            require(bounds.width()*bounds.height() < host.width*host.height*.2f, "reported support bounds describe the small group rather than the entire host")
            for (bitmap in arrayOf(separated,merged,triplet,styled,fallback,resized)) bitmap.recycle()
            main { host.close() }; idle()
            require(host.recomposer.currentState.value == Recomposer.State.ShutDown, "detaching releases the composition and frame work")
            lateinit var session: PanelGlassSession
            main { session=PanelGlassSession(activity,activity.display); session.prepareTension() }; idle()
            val rendererField=PanelGlassSession::class.java.getDeclaredField("tensionRenderer").apply { isAccessible=true }
            require(rendererField.get(session) != null, "idle preparation creates the renderer before any drag")
            main { session.close(); session=PanelGlassSession(activity,activity.display); session.prepareTension(); session.clearViews() }; idle()
            require(rendererField.get(session) == null, "removing hosts cancels pending idle preparation")
            main { session.prepareTension(); session.close() }; idle()
            require(rendererField.get(session) == null, "closing a session cannot resurrect the renderer")
            return "PASS: $assertions component assertions; real Compose state, two-button merge, style, fallback, click, resize and release"
        } finally { main { if (::host.isInitialized) host.close(); activity.finish() } }
    }
    private fun capture(activity: Activity, name: String): Bitmap {
        val bitmap = test.uiAutomation.takeScreenshot() ?: throw AssertionError("No GPU screenshot")
        val directory = File(activity.filesDir,"liquid-tension-component").apply { mkdirs() }
        File(directory,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }
    private class Host(activity: Activity) : FrameLayout(activity), LifecycleOwner, SavedStateRegistryOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = savedState.savedStateRegistry
        val recomposer = Recomposer(AndroidUiDispatcher.Main)
        private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob())
        val compose = ComposeView(activity)
        val geometry = LiquidTensionGeometry()
        var progress by mutableFloatStateOf(0f)
        var style by mutableStateOf(LiquidTensionStyle.Default)
        var effectEnabled by mutableStateOf(true)
        var extra by mutableStateOf(false)
        var clicks = 0
        init {
            savedState.performAttach(); savedState.performRestore(null)
            setViewTreeLifecycleOwner(this); setViewTreeSavedStateRegistryOwner(this)
            compose.setParentCompositionContext(recomposer)
            compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            addView(compose,LayoutParams(-1,-1))
            lifecycle.currentState=Lifecycle.State.RESUMED
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            compose.setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) { drawRect(Color(0xFF314860)) }
                    LiquidTensionSurface(backdrop, {
                        val density=resources.displayMetrics.density
                        val shift=18*progress
                        geometry.reset(); geometry.bevel=22*density; geometry.refraction=18.7f*density
                        val centers = if (extra) floatArrayOf(100+shift,220f,136-shift) else floatArrayOf(100+shift,136-shift)
                        for (center in centers) geometry.add((center-14.4f)*density,85.6f*density,(center+14.4f)*density,114.4f*density,14.4f*density)
                        geometry.mergePair(0,if (extra) 2 else 1,progress,16*density); geometry
                    }, Modifier.fillMaxSize(), style, effectEnabled) {
                        Box(Modifier.offset(24.dp,24.dp).size(24.dp).background(Color.White).clickable { clicks++ })
                    }
                }
            }
        }
        fun close() { if (lifecycle.currentState == Lifecycle.State.DESTROYED) return; compose.disposeComposition(); recomposer.cancel(); scope.cancel(); lifecycle.currentState=Lifecycle.State.DESTROYED }
    }
}
