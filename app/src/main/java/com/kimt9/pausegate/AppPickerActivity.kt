package com.kimt9.pausegate

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 설치된 모든 앱 중에서 개입 대상을 고르고, 앱마다 하루 사용 한도를 정하는 화면 */
class AppPickerActivity : Activity() {

    private lateinit var prefs: Prefs
    private var all: List<AppInfo> = emptyList()
    private var shown: List<AppInfo> = emptyList()
    private val selected = HashSet<String>()
    private lateinit var adapter: Adapter
    private lateinit var countText: TextView

    private val dp: Float get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        selected.addAll(prefs.gated)
        all = Util.launchableApps(this)
        shown = all

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(24), px(16), 0)
        }
        countText = TextView(this).apply { textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        root.addView(countText)

        val search = EditText(this).apply {
            hint = "앱 이름 검색"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = "보이는 앱 모두 선택"
            setOnClickListener { shown.forEach { selected.add(it.pkg) }; commit() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply {
            text = "보이는 앱 모두 해제"
            setOnClickListener { shown.forEach { selected.remove(it.pkg) }; commit() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        adapter = Adapter()
        root.addView(ListView(this).apply { this.adapter = this@AppPickerActivity.adapter; divider = null },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        updateCount()
    }

    private fun filter(q: String) {
        val k = q.trim().lowercase()
        shown = if (k.isEmpty()) all else all.filter { it.label.lowercase().contains(k) }
        adapter.notifyDataSetChanged()
    }

    private fun commit() {
        prefs.gated = selected
        updateCount()
        adapter.notifyDataSetChanged()
    }

    private fun updateCount() {
        countText.text = "개입할 앱 ${selected.size}개 선택됨"
    }

    private fun askLimit(app: AppInfo) {
        val opts = intArrayOf(0, 15, 30, 60, 90, 120, 180)
        val names = opts.map { if (it == 0) "한도 없음" else "하루 ${it}분" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${app.label} 하루 사용 한도")
            .setItems(names) { _, i ->
                prefs.setLimitMin(app.pkg, opts[i])
                adapter.notifyDataSetChanged()
            }.show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = shown[position]
            val row = LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(6), 0, px(6))
            }
            val check = CheckBox(this@AppPickerActivity).apply {
                isChecked = app.pkg in selected
                setOnClickListener {
                    if (isChecked) selected.add(app.pkg) else selected.remove(app.pkg)
                    prefs.gated = selected
                    updateCount()
                }
            }
            row.addView(check)
            row.addView(ImageView(this@AppPickerActivity).apply {
                setImageDrawable(packageManager.getApplicationIcon(app.pkg))
            }, LinearLayout.LayoutParams(px(40), px(40)))
            row.addView(TextView(this@AppPickerActivity).apply {
                text = app.label; textSize = 16f; setPadding(px(12), 0, px(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val limit = prefs.limitMin(app.pkg)
            row.addView(TextView(this@AppPickerActivity).apply {
                text = if (limit == 0) "한도 없음" else "${limit}분/일"
                textSize = 13f
                setTextColor(0xFF3F7D6E.toInt())
                setPadding(px(8), px(8), px(8), px(8))
                setOnClickListener { askLimit(app) }
            })
            row.setOnClickListener { check.performClick() }
            return row
        }
    }
}
