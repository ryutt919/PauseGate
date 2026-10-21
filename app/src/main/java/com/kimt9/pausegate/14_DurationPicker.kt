package com.kimt9.pausegate

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 지속 시간 입력. 취소하거나 잘못 입력하면 기존 설정을 유지한다. */
fun showDurationPicker(activity: Activity, name: String, seconds: Int, minimum: Int, onSave: (Int) -> Unit) {
    fun px(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(24), px(8), px(24), px(8))
    }
    content.addView(TextView(activity).apply {
        text = "${DurationPolicy.format(minimum)}부터 24시간까지 설정할 수 있어요."
        textSize = 16f
    })
    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    val initial = listOf(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    val inputs = listOf("시간", "분", "초").mapIndexed { i, unit ->
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(12), px(4), 0)
        }
        val input = EditText(activity).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(initial[i].toString())
            setSelectAllOnFocus(true)
            gravity = Gravity.CENTER
            minHeight = px(52)
            contentDescription = "$name $unit"
        }
        group.addView(TextView(activity).apply { text = unit; labelFor = input.id; textSize = 16f })
        group.addView(input, LinearLayout.LayoutParams(-1, -2))
        row.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
        input
    }
    content.addView(row)
    val error = TextView(activity).apply { textSize = 14f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    content.addView(error)
    val dialog = AlertDialog.Builder(activity)
        .setTitle(name)
        .setView(ScrollView(activity).apply { addView(content) })
        .setNegativeButton("취소", null)
        .setPositiveButton("저장", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val values = inputs.map { it.text.toString().toIntOrNull() }
            val hours = values[0]
            val minutes = values[1]
            val secs = values[2]
            if (hours == null || hours !in 0..24 || minutes == null || minutes !in 0..59 || secs == null || secs !in 0..59) {
                error.text = "시간은 0~24, 분과 초는 0~59로 입력해 주세요."
            } else {
                val total = hours * 3600 + minutes * 60 + secs
                if (total !in minimum..DurationPolicy.MAX_SECONDS) {
                    error.text = "${DurationPolicy.format(minimum)} 이상, 24시간 이하로 입력해 주세요."
                } else {
                    onSave(total)
                    dialog.dismiss()
                }
            }
        }
    }
    dialog.show()
}
