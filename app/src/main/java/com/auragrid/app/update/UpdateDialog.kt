package com.auragrid.app.update

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.auragrid.app.MainActivity
import com.auragrid.app.databinding.ActivityMainBinding

/**
 * UpdateDialog: Premium cyber-styled dialog for notifying users of OTA package upgrades.
 */
object UpdateDialog {
    fun show(
        activity: MainActivity,
        binding: ActivityMainBinding,
        currentVersion: String,
        remoteVersion: String,
        notes: String,
        sizeBytes: Long,
        downloadUrl: String
    ) {
        activity.runOnUiThread {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)

            // Dynamic language detection
            val sharedPreferences = activity.getSharedPreferences("AuraGridPreferences", Context.MODE_PRIVATE)
            val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
            val isTraditional = lang == "zh-rTW" || lang == "zh-TW"
            val isZh = lang == "zh" || isTraditional

            // Base deep-obsidian container
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#08080C"))
                setPadding(48, 48, 48, 48)
                gravity = Gravity.CENTER_HORIZONTAL
            }

            // Header Section
            val headerLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 40
                }
            }

            val iconView = TextView(activity).apply {
                text = "🚀"
                textSize = 48f
                gravity = Gravity.CENTER
            }
            headerLayout.addView(iconView)

            val titleText = TextView(activity).apply {
                text = when {
                    isTraditional -> "✨ 發現 Aura Grid 伴侶端新版本"
                    isZh -> "✨ 发现 Aura Grid 伴侣端新版本"
                    else -> "✨ New Aura Grid Update Available"
                }
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                }
            }
            headerLayout.addView(titleText)

            val subtitleText = TextView(activity).apply {
                text = "AURA-GRID-OTA-SYSTEM-UPGRADE"
                textSize = 9f
                setTextColor(Color.parseColor("#00E5FF"))
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.2f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }
            headerLayout.addView(subtitleText)
            container.addView(headerLayout)

            // Scrollable Content Area (Glassmorphic card layout)
            val scrollView = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    weight = 1f
                    topMargin = 32
                    bottomMargin = 32
                }
                isVerticalScrollBarEnabled = false
            }

            val cardLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0DFFFFFF"))
                    setStroke(2, Color.parseColor("#2600E5FF"))
                    cornerRadius = 24f
                }
            }

            // Specs rows (Current, Remote, Size)
            val specsLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }
            }

            fun addSpecRow(label: String, value: String) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                }
                val lblText = TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Color.parseColor("#8E8E93"))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        weight = 1f
                    }
                }
                val valText = TextView(activity).apply {
                    text = value
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                row.addView(lblText)
                row.addView(valText)
                specsLayout.addView(row)
            }

            val sizeMb = String.format("%.2f MB", sizeBytes / 1024.0 / 1024.0)
            addSpecRow(if (isZh) "当前版本 / Active Version" else "Active Version", currentVersion)
            addSpecRow(if (isZh) "最新版本 / Latest Version" else "Latest Version", remoteVersion)
            addSpecRow(if (isZh) "升级包体积 / Package Size" else "Package Size", sizeMb)

            cardLayout.addView(specsLayout)

            // Divider line
            val specDivider = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                ).apply {
                    bottomMargin = 24
                }
                setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            }
            cardLayout.addView(specDivider)

            // Release Notes Section
            val notesTitle = TextView(activity).apply {
                text = when {
                    isTraditional -> "📋 更新日誌與功能提升"
                    isZh -> "📋 更新日志与功能提升"
                    else -> "📋 Release Logs & Enhancements"
                }
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
            }
            cardLayout.addView(notesTitle)

            val notesContent = TextView(activity).apply {
                text = if (notes.trim().isEmpty()) {
                    if (isZh) "修复部分已知问题并提升系统稳定性" else "General bug fixes and system performance optimizations."
                } else {
                    notes
                }
                textSize = 12f
                setTextColor(Color.parseColor("#8E8E93"))
                setLineSpacing(0f, 1.3f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            cardLayout.addView(notesContent)

            scrollView.addView(cardLayout)
            container.addView(scrollView)

            // Bottom Buttons
            val buttonsLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }

            val installButton = Button(activity).apply {
                text = when {
                    isTraditional -> "立即安全升級"
                    isZh -> "立即安全升级"
                    else -> "UPGRADE SECURE NOW"
                }
                textSize = 14f
                setTextColor(Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD

                // Cyber teal gradient
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0086FF"))
                ).apply {
                    cornerRadius = 16f
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    120
                ).apply {
                    bottomMargin = 24
                }

                setOnClickListener {
                    dialog.dismiss()
                    ApkDownloader.startDownload(activity, binding, downloadUrl)
                }
            }
            buttonsLayout.addView(installButton)

            val skipButton = TextView(activity).apply {
                text = when {
                    isTraditional -> "暫不更新"
                    isZh -> "暂不更新"
                    else -> "LATER"
                }
                textSize = 13f
                setTextColor(Color.parseColor("#8E8E93"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                isClickable = true

                setOnClickListener {
                    dialog.dismiss()
                }
            }
            buttonsLayout.addView(skipButton)

            container.addView(buttonsLayout)

            dialog.setContentView(container)
            dialog.show()
        }
    }
}
