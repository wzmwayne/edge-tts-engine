package com.edge.tts.edge_tts_engine

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 未捕获异常时的兜底错误页：展示堆栈，用户点击「退出」后结束进程。 */
class EdgeErrorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val err = intent?.getStringExtra("error") ?: "未知错误"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 60, 40, 40)
        }
        val title = TextView(this).apply {
            text = "应用发生错误"
            textSize = 22f
            setTextColor(0xFFD32F2F.toInt())
        }
        val hint = TextView(this).apply {
            text = "错误已写入日志文件，请复制以下内容反馈"
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 8, 0, 16)
        }
        val scroll = ScrollView(this)
        val body = TextView(this).apply {
            text = err
            textSize = 12f
            setTextIsSelectable(true)
            setTextColor(0xFF333333.toInt())
            typeface = Typeface.MONOSPACE
        }
        scroll.addView(body)
        val btn = Button(this).apply {
            text = "退出"
            setOnClickListener {
                Process.killProcess(Process.myPid())
                Runtime.getRuntime().halt(1)
            }
        }
        root.addView(title)
        root.addView(hint)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        root.addView(btn)
        setContentView(root)
    }
}