package com.orbit.browser.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView

/**
 * Otomatik araç çubuğu gizleme/gösterme için kaydırma olaylarını yakalayan
 * ve dokunulduğunda klavye odağını alan WebView.
 */
class OrbitWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var onScrollListener: ((dy: Int, scrollY: Int) -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollListener?.invoke(t - oldt, t)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (!hasFocus()) {
                requestFocus()
            }
        }
        return super.onTouchEvent(event)
    }
}
