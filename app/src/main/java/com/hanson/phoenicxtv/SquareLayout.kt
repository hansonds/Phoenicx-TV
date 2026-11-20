package com.hanson.phoenicxtv

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** A LinearLayout that is always as tall as it is wide — used for the square channel tiles. */
class SquareLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
