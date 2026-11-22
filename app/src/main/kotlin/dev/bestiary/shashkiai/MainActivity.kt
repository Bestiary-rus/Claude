package dev.bestiary.shashkiai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout

interface Screen {
    val view: View
    fun onShow() {}
    fun onHide() {}
    /** Return true if the back press was consumed. */
    fun onBack(): Boolean = false
}

class MainActivity : Activity() {
    private lateinit var container: FrameLayout
    private var current: Screen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Brain.init(this)
        container = FrameLayout(this).apply { setBackgroundColor(Colors.BG) }
        container.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(container)
        show(HomeScreen(this))
    }

    fun show(screen: Screen) {
        current?.onHide()
        container.removeAllViews()
        container.addView(screen.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        current = screen
        screen.onShow()
    }

    fun home() = show(HomeScreen(this))

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = current
        if (s != null && s.onBack()) return
        if (s is HomeScreen) @Suppress("DEPRECATION") super.onBackPressed() else home()
    }

    override fun onDestroy() {
        current?.onHide()
        super.onDestroy()
    }
}
