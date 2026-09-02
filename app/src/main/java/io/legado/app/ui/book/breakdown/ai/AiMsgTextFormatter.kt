package io.legado.app.ui.book.breakdown.ai

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.annotation.ColorInt
import java.util.regex.Pattern

/**
 * AI 回复正文的轻量层级排版(仅展示层,不改业务数据):
 *   - "#/##/###" 标题 → 加粗 + 相对字号
 *   - "- " 与 "* " 开头 → 圆点列表
 *   - "```" 围栏代码块 → 等宽字体 + 浅底色块 + 缩进
 *   - "`code`" 行内代码 → 等宽字体 + 浅底色
 *   - "**bold**" → 加粗
 */
object AiMsgTextFormatter {

    private val HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$")
    private val BULLET = Pattern.compile("^[-*•]\\s+(.*)$")
    private val INLINE = Pattern.compile("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*")

    fun format(content: String, @ColorInt codeBg: Int): CharSequence {
        val needsFormat = content.contains('#') || content.contains('`') || content.contains("**")
            || content.contains("```") || content.contains("\n- ") || content.contains("\n* ")
            || content.startsWith("- ") || content.startsWith("* ")
        if (!needsFormat) {
            // 无 markdown 特征时原样返回,流式追加时零开销
            return content
        }
        val ssb = SpannableStringBuilder()
        val codeBuf = StringBuilder()
        var inCode = false

        content.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    flushCodeBlock(ssb, codeBuf, codeBg)
                    codeBuf.setLength(0)
                }
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                codeBuf.append(line).append('\n')
                return@forEach
            }
            if (trimmed.isEmpty()) {
                ssb.append('\n')
                return@forEach
            }
            val heading = HEADING.matcher(trimmed)
            if (heading.find()) {
                if (ssb.isNotEmpty() && !ssb.endsWith('\n')) ssb.append('\n')
                val level = heading.group(1).length.coerceAtMost(3)
                val start = ssb.length
                appendInline(ssb, heading.group(2), codeBg)
                ssb.setSpan(
                    RelativeSizeSpan(if (level == 1) 1.22f else if (level == 2) 1.12f else 1.05f),
                    start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append('\n')
                return@forEach
            }
            val bullet = BULLET.matcher(trimmed)
            if (bullet.find()) {
                val start = ssb.length
                appendInline(ssb, bullet.group(1), codeBg)
                ssb.setSpan(BulletSpan(12), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.append('\n')
                return@forEach
            }
            // 有序列表与普通段落原样排版(保留编号视觉)
            appendInline(ssb, trimmed, codeBg)
            ssb.append('\n')
        }
        if (inCode && codeBuf.isNotEmpty()) flushCodeBlock(ssb, codeBuf, codeBg)
        // 去掉末尾多余换行
        while (ssb.isNotEmpty() && ssb.endsWith('\n')) {
            ssb.delete(ssb.length - 1, ssb.length)
        }
        return ssb
    }

    private fun flushCodeBlock(ssb: SpannableStringBuilder, codeBuf: StringBuilder, @ColorInt codeBg: Int) {
        if (codeBuf.isEmpty()) return
        if (ssb.isNotEmpty() && !ssb.endsWith('\n')) ssb.append('\n')
        val start = ssb.length
        ssb.append(codeBuf)
        ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(BackgroundColorSpan(codeBg), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(LeadingMarginSpan.Standard(12, 12), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.append('\n')
    }

    private fun appendInline(ssb: SpannableStringBuilder, text: String, @ColorInt codeBg: Int) {
        val m = INLINE.matcher(text)
        var last = 0
        while (m.find()) {
            ssb.append(text, last, m.start())
            if (m.group(1) != null) {
                val start = ssb.length
                ssb.append(m.group(1))
                ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(BackgroundColorSpan(codeBg), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (m.group(2) != null) {
                val start = ssb.length
                ssb.append(m.group(2))
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.end()
        }
        ssb.append(text, last, text.length)
    }

    private fun SpannableStringBuilder.endsWith(c: Char): Boolean {
        return isNotEmpty() && this[length - 1] == c
    }
}
