package com.kimt9.pausegate

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Choose a total quota in hours/minutes; the callback receives total minutes. */
fun showCycleExtensionPicker(
    context: Context,
    appLabel: String,
    currentLimitMs: Long,
    overlay: Boolean = false,
    onSave: (Int) -> Boolean,
): AlertDialog {
    val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val minimumMinutes = (currentLimitMs / 60_000L + 1L).toInt()
    val content = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }
    content.addView(TextView(themed).apply {
        text = "$appLabel 이용 한도를 이번 주기만 늘려요.\n이미 사용한 시간은 유지하고, 다음 주기는 원래 한도로 돌아가요.\n쿨타임 중이라면 쿨타임을 끝내고 남은 한도를 이용해요."
        textSize = 16f
    })
    content.addView(TextView(themed).apply {
        text = "현재 총 한도 ${DurationPolicy.format((currentLimitMs / 1000L).toInt())}\n새 총 한도: ${DurationPolicy.format(minimumMinutes * 60)}부터 24시간까지"
        textSize = 16f
        setPadding(0, dp(16), 0, 0)
    })
    val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
    val initial = listOf(minimumMinutes / 60, minimumMinutes % 60)
    val inputs = listOf("시간", "분").mapIndexed { i, unit ->
        val group = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), 0)
        }
        val input = EditText(themed).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            minHeight = dp(48)
            gravity = Gravity.CENTER
            contentDescription = "이번 주기 총 이용 한도 $unit"
            setText(initial[i].toString())
            setSelectAllOnFocus(true)
        }
        group.addView(TextView(themed).apply { text = unit; labelFor = input.id; textSize = 16f })
        group.addView(input, LinearLayout.LayoutParams(-1, -2))
        row.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
        input
    }
    content.addView(row)
    val error = TextView(themed).apply {
        textSize = 14f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    content.addView(error)
    fun validMinutes(): Int? {
        val hours = inputs[0].text.toString().toIntOrNull() ?: return null
        val minutes = inputs[1].text.toString().toIntOrNull() ?: return null
        if (hours !in 0..24 || minutes !in 0..59) return null
        return (hours * 60 + minutes).takeIf { it in minimumMinutes..1440 }
    }
    val rangeError = "시간은 0〜24, 분은 0〜59, 총 한도는 ${DurationPolicy.format(minimumMinutes * 60)} 이상 24시간 이하로 입력해 주세요."
    val dialog = AlertDialog.Builder(themed)
        .setTitle("이번 주기만 시간 늘리기")
        .setView(android.widget.ScrollView(themed).apply { addView(content) })
        .setNegativeButton("취소", null)
        .setPositiveButton("늘리기", null)
        .create()
    if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    inputs.forEach { input -> input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            error.text = if (validMinutes() == null) rangeError else ""
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }) }
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val minutes = validMinutes()
            if (minutes == null) error.text = rangeError
            else if (onSave(minutes)) dialog.dismiss()
            else error.text = "한도가 변경됐어요. 취소 후 다시 시도해 주세요."
        }
    }
    dialog.show()
    return dialog
}
