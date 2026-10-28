package io.github.jichuo1.meguviz.android.extensions

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Only SDK predicates actually used by the migrating consumer. No runtime probing. */
object AndroidVersion {
    const val P = 28
    const val Q = 29
    const val R = 30
    const val S = 31
    const val T = 33
    const val U = 34
    const val BAKLAVA = 36
    val code: Int get() = Build.VERSION.SDK_INT

    @ChecksSdkIntAtLeast(parameter = 0)
    fun isAtLeast(target: Int): Boolean = Build.VERSION.SDK_INT >= target
    fun isLessThan(target: Int): Boolean = Build.VERSION.SDK_INT < target
    fun isAtMost(target: Int): Boolean = Build.VERSION.SDK_INT <= target
}

val PackageInfo.versionCodeCompat: Long
    @Suppress("DEPRECATION")
    get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

val Configuration.isUiInNightMode: Boolean
    get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Converts dp, preserving negative layout sentinels and the prior truncation rule. */
fun <N : Number> N.toPx(context: Context): N = toPx(context.resources)

fun <N : Number> N.toPx(resources: Resources): N = dpToPixels(this, resources.displayMetrics.density)

@Suppress("UNCHECKED_CAST")
internal fun <N : Number> dpToPixels(value: N, density: Float): N {
    val scalar = value.toFloat().let { if (it >= 0f) it * density else it }
    return when (value) {
        is Int -> scalar.toInt()
        is Long -> scalar.toLong()
        is Float -> scalar
        is Double -> scalar.toDouble()
        else -> error("Supported dimension types: Int, Long, Float, Double.")
    } as N
}

/** Platform conversion retains modern nonlinear font scaling. Negative sp is not a sentinel. */
fun Float.spToPx(resources: Resources): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)

/** No background queue is created. Off-main callers must use their owned scheduler. */
fun Context.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    check(Looper.myLooper() === Looper.getMainLooper()) { "Toast must be shown on the main thread." }
    Toast.makeText(this, message, duration).show()
}

fun View.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) = context.toast(message, duration)
fun Window.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) = context.toast(message, duration)
fun Dialog.toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT) = context.toast(message, duration)

fun PackageManager.enableComponent(component: ComponentName, vararg flags: Int) {
    setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        flags.fold(PackageManager.DONT_KILL_APP) { result, flag -> result or flag })
}

fun PackageManager.disableComponent(component: ComponentName, vararg flags: Int) {
    setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        flags.fold(PackageManager.DONT_KILL_APP) { result, flag -> result or flag })
}

/**
 * Effective activity/alias availability, including the application's enabled state.
 * DEFAULT consults manifest information; disabled states are never reported enabled.
 * Missing activity/alias propagates PackageManager.NameNotFoundException.
 */
@Suppress("DEPRECATION")
fun PackageManager.isActivityComponentEnabled(component: ComponentName): Boolean {
    val info = getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
    val applicationEnabled = effectiveEnabled(
        getApplicationEnabledSetting(component.packageName), info.applicationInfo.enabled
    )
    return applicationEnabled && effectiveEnabled(getComponentEnabledSetting(component), info.enabled)
}

internal fun effectiveEnabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
    else -> false
}

/** Observe only: never installs a listener or consumes host insets. Null means unavailable. */
fun View.rootStatusBarInsetTopOrNull(): Int? =
    ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
