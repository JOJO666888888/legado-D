package io.legado.app.help.breakdown

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.databinding.FloatingBreakdownBallBinding
import io.legado.app.databinding.FloatingBreakdownPanelBinding
import io.legado.app.utils.dpToPx
import kotlin.math.abs

/**
 * 拆书原文悬浮窗管理器(Activity 级)
 *
 * - 悬浮球:可拖动、松手后自动吸附到左/右边缘,点击展开面板
 * - 展开面板:占 92% 宽 x 70% 高,居中展示,用于浏览章节原文与收录素材
 * - 生命周期:跟随传入的 [FragmentActivity],ON_DESTROY 自动从 decorView 移除
 * - 无需 SYSTEM_ALERT_WINDOW 权限,因为 View 注入到 Activity 自身 decorView
 */
@SuppressLint("ClickableViewAccessibility")
class BreakdownFloatingWindow(
    private val activity: FragmentActivity,
    private val lifecycle: Lifecycle,
    private val initialBookUrl: String? = null,
    private val initialChapterIndex: Int? = null,
    private val defaultToExpanded: Boolean = false,
) : LifecycleObserver {

    private val decorView: FrameLayout by lazy { activity.window.decorView as FrameLayout }
    private val inflater: LayoutInflater by lazy { LayoutInflater.from(activity) }

    private var ballBinding: FloatingBreakdownBallBinding? = null
    private var panelBinding: FloatingBreakdownPanelBinding? = null
    private var controller: FloatingContent? = null

    // 球的初始位置(右边缘 + 1/3 高度)
    private var ballInitX: Int = 0
    private var ballInitY: Int = 0

    fun attach() {
        lifecycle.addObserver(this)
        addBallView()
        if (defaultToExpanded) {
            expandPanel()
        }
    }

    /* =============================== 生命周期 =============================== */

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun detach() {
        removeBallView()
        removePanelView()
        controller = null
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            lifecycle.removeObserver(this)
        }
    }

    /* =============================== 悬浮球 =============================== */

    private fun addBallView() {
        if (ballBinding != null) return
        val binding = FloatingBreakdownBallBinding.inflate(inflater, decorView, false)
        ballBinding = binding
        val lp = binding.root.layoutParams as FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        val dm = activity.resources.displayMetrics
        val ballSize = 56.dpToPx()
        ballInitX = dm.widthPixels - ballSize - 12.dpToPx()
        ballInitY = dm.heightPixels / 3
        lp.leftMargin = ballInitX
        lp.topMargin = ballInitY
        binding.root.layoutParams = lp

        binding.root.setOnTouchListener(BallTouchListener())
        binding.root.setOnClickListener { expandPanel() }

        decorView.addView(binding.root)
    }

    private fun removeBallView() {
        val binding = ballBinding ?: return
        ballBinding = null
        runCatching { decorView.removeView(binding.root) }
    }

    private fun showBallView() {
        ballBinding?.root?.visibility = View.VISIBLE
    }

    private fun hideBallView() {
        ballBinding?.root?.visibility = View.GONE
    }

    /* =============================== 展开面板 =============================== */

    private fun expandPanel() {
        if (panelBinding != null) return
        val binding = FloatingBreakdownPanelBinding.inflate(inflater, decorView, false)
        panelBinding = binding
        val dm = activity.resources.displayMetrics
        val lp = FrameLayout.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(),
            (dm.heightPixels * 0.70f).toInt()
        ).apply {
            gravity = android.view.Gravity.CENTER
        }
        binding.root.layoutParams = lp

        val ctrl = FloatingContent(
            activity = activity,
            binding = binding,
            scope = activity.lifecycleScope,
            initialBookUrl = initialBookUrl,
            initialChapterIndex = initialChapterIndex,
            onClose = { collapsePanel() }
        )
        controller = ctrl
        ctrl.init()

        decorView.addView(binding.root)
        hideBallView()
    }

    private fun removePanelView() {
        val binding = panelBinding ?: return
        panelBinding = null
        controller = null
        runCatching { decorView.removeView(binding.root) }
    }

    private fun collapsePanel() {
        removePanelView()
        showBallView()
    }

    /* =============================== 拖动监听 =============================== */

    private inner class BallTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var isClick = true

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isClick = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isClick = false
                    }
                    if (!isClick) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        val dm = activity.resources.displayMetrics
                        lp.leftMargin = (startLeft + dx).toInt()
                            .coerceIn(0, dm.widthPixels - v.width)
                        lp.topMargin = (startTop + dy).toInt()
                            .coerceIn(0, dm.heightPixels - v.height - 48.dpToPx())
                        v.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        v.performClick()
                    } else {
                        snapToEdge(v)
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge(ball: View) {
        val dm = activity.resources.displayMetrics
        val lp = ball.layoutParams as FrameLayout.LayoutParams
        val centerX = lp.leftMargin + ball.width / 2f
        lp.leftMargin = if (centerX < dm.widthPixels / 2f) {
            0 // 吸附到左侧
        } else {
            dm.widthPixels - ball.width // 吸附到右侧
        }
        ball.layoutParams = lp
    }
}
