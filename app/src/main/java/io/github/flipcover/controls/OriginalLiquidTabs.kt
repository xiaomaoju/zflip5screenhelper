package io.github.flipcover.controls

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import java.util.function.IntConsumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Supplies content, cached pixels and a window lifetime. Upstream effect files are unchanged. */
@android.annotation.TargetApi(33)
internal class OriginalLiquidTabs(context: Context, private val choose: IntConsumer, private val labels: Array<String>, private val icons: IntArray, private val heightDp: Int) : FrameLayout(context) {
    constructor(context: Context, choose: IntConsumer) : this(context, choose, arrayOf("内置功能", "应用磁贴", "应用"), intArrayOf(R.drawable.ic_ms_tune, R.drawable.ic_ms_view_carousel, R.drawable.ic_ms_apps), ControlSourceTabs.HEIGHT)
    private var selectedIndex by mutableIntStateOf(0)
    private var generation by mutableIntStateOf(0)
    private var backgroundSession by mutableStateOf<PanelGlassSession?>(null)
    private var backgroundRevision by mutableIntStateOf(0)
    private var compactLabels by mutableStateOf(false)
    private var owner = HostOwner()
    private val compose = ComposeView(context)
    private var recomposer: Recomposer? = null
    private var compositionScope: CoroutineScope? = null

    init {
        isFocusable = true
        isClickable = true
        contentDescription = labels.joinToString(" / ")
        clipChildren = false
        clipToPadding = false
        installOwner()
        ensureRecomposer()
        compose.clipChildren = false
        compose.clipToPadding = false
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        compose.setContent {
            if (backgroundSession == null) return@setContent
            // Scale the complete original component to the native host's strip height.
            val density = Density(resources.displayMetrics.density * heightDp / 64f, resources.configuration.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                val readSelection = remember { { selectedIndex } }
                val epoch = generation
                key(epoch) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val backdrop = rememberLayerBackdrop()
                        var canvasOffset by remember { mutableStateOf(Offset.Zero) }
                        Canvas(Modifier.requiredSize(maxWidth + 32.dp, maxHeight + 32.dp).alpha(0f)
                            .onGloballyPositioned { canvasOffset = it.positionInRoot() }.layerBackdrop(backdrop)) {
                            backgroundRevision
                            drawIntoCanvas { backgroundSession?.drawLocalBackdrop(it.nativeCanvas, this@OriginalLiquidTabs, canvasOffset.x, canvasOffset.y) }
                        }
                        LiquidBottomTabs(readSelection, { index -> if (epoch == generation && index != selectedIndex) choose.accept(index) }, backdrop, labels.size, Modifier.fillMaxWidth()) {
                            val visibleLabels = if (compactLabels && icons.isNotEmpty()) arrayOf("内置", "磁贴", "应用") else labels
                            visibleLabels.forEachIndexed { index, label ->
                                val active = selectedIndex == index
                                val color = if (active) Color(0xFF0091FF) else Color(0xFFF4F7FA)
                                LiquidBottomTab({ if (epoch == generation) choose.accept(index) }, Modifier.semantics { selected = active; contentDescription = label }) {
                                    if (icons.isNotEmpty()) Image(painterResource(icons[index]), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(color))
                                    BasicText(label, style = TextStyle(color = color, fontSize = if (icons.isEmpty()) 21.sp else 16.sp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    fun source(index: Int) { selectedIndex = index }
    fun inputStep(forward: Boolean) { val next = (selectedIndex + if (forward) 1 else -1).coerceIn(0, labels.lastIndex); if (next != selectedIndex) choose.accept(next) }
    fun glass(session: PanelGlassSession?) {
        backgroundSession = session
        if (session == null) compose.disposeComposition()
        else if (isAttachedToWindow) compose.createComposition()
    }
    fun backdropMoved() { backgroundRevision++ }
    fun cancelGesture() { generation++ }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        compactLabels = w / labels.size.toFloat() < Ui.dp(context, 62f)
    }
    private fun installOwner() { setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner) }
    private fun ensureRecomposer() {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) { owner = HostOwner(); installOwner() }
        if (recomposer == null) {
            recomposer = Recomposer(AndroidUiDispatcher.Main)
            compose.setParentCompositionContext(recomposer)
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        ensureRecomposer()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onAttachedToWindow() {
        ensureRecomposer()
        super.onAttachedToWindow()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val active = recomposer!!
        compositionScope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob()).also { scope -> scope.launch { active.runRecomposeAndApplyChanges() } }
    }
    override fun onDetachedFromWindow() {
        compose.disposeComposition()
        recomposer?.cancel()
        compositionScope?.cancel()
        recomposer = null
        compositionScope = null
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }
    private class HostOwner : LifecycleOwner, SavedStateRegistryOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry
        init { controller.performAttach(); controller.performRestore(null); lifecycle.currentState = Lifecycle.State.CREATED }
    }
}
