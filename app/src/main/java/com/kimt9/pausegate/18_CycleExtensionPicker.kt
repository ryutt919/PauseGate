package com.kimt9.pausegate

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Choose a total quota in hours; saving delegates all state changes to the repository owner. */
fun showCycleExtensionPicker(
    context: Context,
    appLabel: String,
    currentLimitMs: Long,
    overlay: Boolean = false,
    onSave: (Int) -> Boolean,
): AlertDialog {
    val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    val minimumHours = (currentLimitMs / 3_600_000L + 1L).toInt()
    val content = LinearLayout(themed).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
    }
    content.addView(TextView(themed).apply {
        text = "$appLabel 이용 한도를 이번 주기만 늘려요.\n이미 사용한 시간은 유지하고, 다음 주기는 원래 한도로 돌아가요.\n쿨타임 중이라면 쿨타임을 끝내고 남은 한도를 이용해요."
        textSize = 16f
    })
    content.addView(TextView(themed).apply {
        text = "이번 주기 총 이용 한도 (시간, $minimumHours〜24)"
        textSize = 16f
        setPadding(0, dp(16), 0, 0)
    })
    val input = EditText(themed).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        minHeight = dp(48)
        contentDescription = "이번 주기 총 이용 한도 시간"
        setText(minimumHours.toString())
        setSelectAllOnFocus(true)
    }
    content.addView(input)
    fun validHours(): Int? = input.text.toString().toIntOrNull()?.takeIf { it in minimumHours..24 }
    val dialog = AlertDialog.Builder(themed)
        .setTitle("이번 주기만 시간 늘리기")
        .setView(android.widget.ScrollView(themed).apply { addView(content) })
        .setNegativeButton("취소", null)
        .setPositiveButton("늘리기", null)
        .create()
    if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            input.error = if (validHours() == null) "$minimumHours〜24시간으로 입력해 주세요." else null
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hours = validHours()
            if (hours == null) input.error = "$minimumHours〜24시간으로 입력해 주세요."
            else if (onSave(hours)) dialog.dismiss()
            else input.error = "한도가 변경됐어요. 취소 후 다시 시도해 주세요."
        }
    }
    dialog.show()
    return dialog
}
