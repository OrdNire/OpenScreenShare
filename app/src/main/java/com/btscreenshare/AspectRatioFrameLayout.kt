package com.btscreenshare

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var aspectRatio = 16f / 9f
    private var aspectRatioSet = false

    // Store the computed child dimensions for use in onLayout
    private var childWidth = 0
    private var childHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            aspectRatio = width.toFloat() / height.toFloat()
            aspectRatioSet = true
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Layout always fills available space
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        setMeasuredDimension(widthSize, heightSize)

        if (!aspectRatioSet || childCount == 0) {
            // No aspect ratio set, measure children normally
            measureChildren(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // Calculate child size that fits within layout while maintaining aspect ratio
        val layoutWidth = widthSize - paddingLeft - paddingRight
        val layoutHeight = heightSize - paddingTop - paddingBottom

        // Cover mode: fill entire screen, crop excess (no black bars)
        val screenRatio = layoutWidth.toFloat() / layoutHeight.toFloat()
        if (aspectRatio > screenRatio) {
            // Video wider than screen - fill height, crop width
            childHeight = layoutHeight
            childWidth = (layoutHeight * aspectRatio).toInt()
        } else {
            // Video taller than screen - fill width, crop height
            childWidth = layoutWidth
            childHeight = (layoutWidth / aspectRatio).toInt()
        }

        // Measure child with the constrained size
        val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)

        for (i in 0 until childCount) {
            getChildAt(i).measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!aspectRatioSet || childCount == 0) {
            // No aspect ratio set, layout children normally
            super.onLayout(changed, left, top, right, bottom)
            return
        }

        val layoutWidth = right - left
        val layoutHeight = bottom - top

        // Center the child within the layout
        val childLeft = (layoutWidth - childWidth) / 2 + paddingLeft
        val childTop = (layoutHeight - childHeight) / 2 + paddingTop

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        }
    }
}