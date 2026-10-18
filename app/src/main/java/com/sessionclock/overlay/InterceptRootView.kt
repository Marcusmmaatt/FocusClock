package com.sessionclock.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * # 浮层拦截根布局（交互规范第 3 节「触摸手势与物理键防御」）
 *
 * 三重职责合一：
 * 1. **触摸拦截**：[dispatchTouchEvent] 始终返回 true，全屏吞掉所有触控
 *    （C01 点外部无响应、C02 下层应用不可交互）；先调 super 保证卡片按钮可点；
 * 2. **返回键拦截**：[dispatchKeyEvent] 吞掉 KEYCODE_BACK 的按下与抬起；
 * 3. **Compose 生命周期宿主**：自身实现 [LifecycleOwner] / [ViewModelStoreOwner] /
 *    [SavedStateRegistryOwner]。androidx.lifecycle 2.8 起 findViewTree* 只读取
 *    View 上的 Tag（`getTag(R.id.view_tree_*)`），不再把父 View 直接 cast 为这些
 *    接口。因此必须由 [FloatWindowManager] 在「窗口根视图」（本布局）上调用
 *    `setViewTree*Owner(this)` 写入 Tag，Compose 的 WindowRecomposer 与内层
 *    ComposeView 才能沿父布局命中这三大 Owner。
 *
 * 生命周期方法与 WindowManager 显隐对齐：
 * addView 前 [onCreate] → addView 后 [onResume]；removeView 时 [onPause]→[onDestroy]。
 */
internal class InterceptRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val viewModelStoreInstance = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ============================== 生命周期（由 FloatWindowManager 驱动） ==============================

    fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStoreInstance.clear()
    }

    // ============================== 按键 / 触摸防御 ==============================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // 按下与抬起都消费，避免 ACTION_UP 漏给下层
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }
}
