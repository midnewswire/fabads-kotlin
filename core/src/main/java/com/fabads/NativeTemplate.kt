package com.fabads

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The views of a native ad in one of the [NativeStyle] layouts, built in code so every network looks the same.
 * A network module fills the texts, puts its own media view into [media] (and, for networks that need it, its own
 * icon view into [iconHolder]), then registers these views with its SDK.
 */
class NativeTemplate(private val context: Context, val style: NativeStyle, dark: Boolean) {
    private val fg = if (dark) Color.WHITE else 0xFF1F1F1F.toInt()
    private val fgSecondary = if (dark) 0xFFBDBDBD.toInt() else 0xFF5F6368.toInt()
    private val bg = if (dark) 0xFF1E1E1E.toInt() else Color.WHITE
    private val accent = 0xFF1A73E8.toInt()

    /** The whole card. Networks that need their own root view wrap this one. */
    val root = FrameLayout(context).apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val iconHolder = FrameLayout(context).apply { addView(icon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
    val headline = text(15f, fg, bold = true, lines = if (style == NativeStyle.NEWS) 2 else 1)
    val body = text(13f, fgSecondary, lines = if (style == NativeStyle.SMALL || style == NativeStyle.NEWS) 1 else 2)
    val advertiser = text(12f, fgSecondary, lines = 1)
    val badge = text(10f, Color.BLACK, bold = true, lines = 1).apply {
        text = "Ad"
        setPadding(dp(4), 0, dp(4), 0)
        background = GradientDrawable().apply { setColor(0xFFFBBC04.toInt()); cornerRadius = dp(3).toFloat() }
    }
    val cta = Button(context).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(accent)
        textSize = 14f
        minHeight = 0
        minimumHeight = 0
    }

    /** Where the network's media view goes. Null for layouts without media ([NativeStyle.SMALL], [NativeStyle.RADIO]). */
    val media: FrameLayout? = when (style) {
        NativeStyle.SMALL, NativeStyle.RADIO -> null
        else -> FrameLayout(context)
    }

    /** Top-right corner for the network's AdChoices / options icon. */
    val adChoices = FrameLayout(context)

    init {
        val pad = dp(10)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val iconSize = dp(if (style == NativeStyle.RADIO) 40 else 44)
        when (style) {
            NativeStyle.SMALL, NativeStyle.RADIO -> {
                val row = row()
                row.addView(iconHolder, LinearLayout.LayoutParams(iconSize, iconSize))
                val texts = column().apply {
                    addView(row(badge, headline))
                    if (style == NativeStyle.SMALL) addView(body) else addView(advertiser)
                }
                row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
                row.addView(cta, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
                content.addView(row)
            }
            NativeStyle.NEWS -> {
                val row = row()
                row.addView(media!!, LinearLayout.LayoutParams(dp(120), dp(90)))
                val texts = column().apply {
                    addView(row(badge, advertiser))
                    addView(headline)
                    addView(body)
                }
                row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                content.addView(row)
                content.addView(cta, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
            }
            NativeStyle.MEDIUM, NativeStyle.LARGE, NativeStyle.VIDEO -> {
                val header = row()
                header.addView(iconHolder, LinearLayout.LayoutParams(iconSize, iconSize))
                header.addView(
                    column().apply { addView(headline); addView(row(badge, advertiser)) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) },
                )
                val mediaHeight = dp(when (style) { NativeStyle.LARGE -> 240; NativeStyle.VIDEO -> 200; else -> 160 })
                val mediaParams = LinearLayout.LayoutParams(MATCH_PARENT, mediaHeight).apply { topMargin = dp(8); bottomMargin = dp(8) }
                if (style == NativeStyle.VIDEO) {
                    content.addView(media!!, LinearLayout.LayoutParams(MATCH_PARENT, mediaHeight).apply { bottomMargin = dp(8) })
                    content.addView(header)
                } else {
                    content.addView(header)
                    content.addView(media!!, mediaParams)
                }
                content.addView(body)
                content.addView(cta, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
            }
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(adChoices, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
    }

    /** Fills the texts and the icon; blank values hide their view. */
    fun bind(headline: String?, body: String?, advertiser: String?, callToAction: String?, icon: Drawable? = null) {
        set(this.headline, headline)
        set(this.body, body)
        set(this.advertiser, advertiser)
        cta.text = callToAction?.takeIf { it.isNotBlank() } ?: "Open"
        if (icon != null) this.icon.setImageDrawable(icon)
        iconHolder.visibility = if (icon == null && this.icon.drawable == null && iconHolder.childCount <= 1) View.GONE else View.VISIBLE
    }

    /** Replaces the icon with the network's own icon view (Meta, for example). */
    fun useIconView(view: View) {
        iconHolder.removeAllViews()
        iconHolder.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        iconHolder.visibility = View.VISIBLE
    }

    /** Puts the network's media view into [media]. */
    fun useMediaView(view: View) {
        media?.removeAllViews()
        media?.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
    }

    private fun set(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun text(size: Float, color: Int, bold: Boolean = false, lines: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun row(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEachIndexed { i, child ->
            addView(child, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(6) })
        }
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(value: Int) = AdUtils.dp(context, value)
}
