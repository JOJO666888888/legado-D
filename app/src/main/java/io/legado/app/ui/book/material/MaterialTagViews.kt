package io.legado.app.ui.book.material

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import io.legado.app.help.material.MaterialHelper
import io.legado.app.utils.dpToPx

/**
 * 素材标签药丸视图(自绘,不依赖 Material Chip,兼容 AppCompat 主题)
 */
object MaterialTagViews {

    /**
     * @param closable 显示关闭符号,点击整个药丸触发 [onClose]
     */
    fun newPill(
        context: Context,
        text: String,
        closable: Boolean = false,
        selected: Boolean = false,
        onClick: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null
    ): TextView {
        val color = MaterialHelper.tagColor(text)
        val pill = TextView(context)
        pill.text = if (closable) "$text ✕" else text
        pill.textSize = 12f
        pill.includeFontPadding = false
        pill.setTextColor(if (selected) Color.WHITE else color)
        val padH = 12.dpToPx()
        val padV = 5.dpToPx()
        pill.setPadding(padH, padV, padH, padV)
        pill.background = GradientDrawable().apply {
            cornerRadius = 999.dpToPx().toFloat()
            setColor(if (selected) color else Color.TRANSPARENT)
            setStroke(1.dpToPx(), color)
        }
        pill.setOnClickListener {
            if (closable && onClose != null) {
                onClose.invoke()
            } else {
                onClick?.invoke()
            }
        }
        return pill
    }
}
