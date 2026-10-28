package io.github.jichuo1.meguviz.android.extensions

import android.view.View
import android.view.ViewGroup
import android.widget.TextView

var TextView.textColor: Int
    get() = currentTextColor
    set(value) { setTextColor(value) }

fun TextView.textToString(): String = text?.toString() ?: ""

@JvmName("typedChild")
inline fun <reified V : View> ViewGroup.child(index: Int): V =
    getChildAt(index) as? V ?: error("Child at $index is absent or has the wrong type.")

fun ViewGroup.child(index: Int): View = child<View>(index)

@JvmName("typedChildOrNull")
inline fun <reified V : View> ViewGroup.childOrNull(index: Int): V? = getChildAt(index) as? V

fun ViewGroup.childOrNull(index: Int): View? = childOrNull<View>(index)

inline fun <reified V : View> ViewGroup.firstChildOrNull(): V? = childOrNull<V>(0)

val ViewGroup.firstChildOrNull: View? get() = getChildAt(0)

@JvmName("typedParentOrNull")
inline fun <reified V : ViewGroup> View.parentOrNull(): V? = parent as? V

fun View.parentOrNull(): ViewGroup? = parent as? ViewGroup

fun View.removeSelf() { (parent as? ViewGroup)?.removeView(this) }

/** Negative axes retain their existing absolute edges. Values are pixels. */
fun View.updatePadding(horizontal: Int = -1, vertical: Int = -1) {
    if (horizontal < 0 && vertical < 0) return
    setPadding(
        if (horizontal >= 0) horizontal else paddingLeft,
        if (vertical >= 0) vertical else paddingTop,
        if (horizontal >= 0) horizontal else paddingRight,
        if (vertical >= 0) vertical else paddingBottom
    )
}

/** Mutates parameters only; their owner decides when to request layout. */
fun ViewGroup.MarginLayoutParams.updateMargins(horizontal: Int = -1, vertical: Int = -1) {
    if (horizontal >= 0) { leftMargin = horizontal; rightMargin = horizontal }
    if (vertical >= 0) { topMargin = vertical; bottomMargin = vertical }
}

fun View.updateMargins(horizontal: Int = -1, vertical: Int = -1) {
    if (horizontal < 0 && vertical < 0) return
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.updateMargins(horizontal, vertical)
    layoutParams = params
}

/** Wrong or missing LayoutParams are a no-op, matching the migrated entry. */
fun View.setMargins(left: Int, top: Int, right: Int, bottom: Int) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.setMargins(left, top, right, bottom)
    layoutParams = params
}

fun View.setMargins(size: Int) = setMargins(size, size, size, size)
