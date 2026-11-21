package com.jarvis.assistant.ui.directives

import android.text.InputFilter
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.macro.MacroCompiler
import com.jarvis.assistant.automation.macro.WorkflowDraftStep
import com.jarvis.assistant.automation.macro.buildWorkflowJson
import com.jarvis.assistant.automation.mission.ToolDefinition
import java.util.Locale

internal fun AppCompatActivity.showWorkflowBuilder(onReview: (String) -> Unit) {
    val activity = this
    val padding = (20 * resources.displayMetrics.density).toInt()
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(padding) }
    fun scroll(content: LinearLayout) = ScrollView(activity).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) }
    val content = column()
    val nameInput = EditText(this).apply {
        hint = getString(R.string.workflow_name)
        setSingleLine(true)
        isSaveEnabled = false
        filters = arrayOf(InputFilter.LengthFilter(160))
    }
    content.addView(nameInput)
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    content.addView(list)
    val steps = mutableListOf<WorkflowDraftStep>()
    val tools = MacroCompiler.builderTools()
    val addButton = MaterialButton(this).apply { setText(R.string.workflow_add_step) }
    content.addView(addButton, MATCH_PARENT, WRAP_CONTENT)

    fun toolLabel(tool: ToolDefinition): String = getString(when (tool.name) {
        "open_app" -> R.string.workflow_tool_open_app
        "inspect_screen" -> R.string.workflow_tool_inspect
        "click_ui_element" -> R.string.workflow_tool_click
        "type_text" -> R.string.workflow_tool_type
        "scroll_screen" -> R.string.workflow_tool_scroll
        "wait_for_screen_text" -> R.string.workflow_tool_wait
        else -> error("Unknown builder tool")
    })

    fun refreshSteps() {
        list.removeAllViews()
        steps.forEachIndexed { index, step ->
            list.addView(MaterialButton(activity).apply {
                text = getString(R.string.workflow_numbered_step, index + 1, toolLabel(step.tool))
                isAllCaps = false
                setOnClickListener {
                    MaterialAlertDialogBuilder(activity).setTitle(text)
                        .setMessage(step.fields.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.workflow_remove_step) { _, _ ->
                            steps.removeAt(index)
                            refreshSteps()
                        }.show()
                }
            }, MATCH_PARENT, WRAP_CONTENT)
        }
        addButton.isEnabled = steps.size < 24
    }

    fun configureStep(tool: ToolDefinition) {
        val fields = column()
        val readValues = tool.parameters.associateWith { parameter ->
            fields.addView(TextView(activity).apply { text = parameter.description })
            if (parameter.type.equals("boolean", ignoreCase = true)) {
                val entries = if (parameter.required) listOf("true", "false") else listOf("", "true", "false")
                val select = Spinner(activity).apply {
                    contentDescription = parameter.description
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                        entries.map { if (it.isEmpty()) getString(R.string.workflow_default_value) else it })
                }
                fields.addView(select, MATCH_PARENT, WRAP_CONTENT)
                val read: () -> String = { entries.getOrElse(select.selectedItemPosition) { "" } }
                read
            } else {
                val number = parameter.type.lowercase(Locale.ROOT) in setOf("int", "integer")
                val input = EditText(activity).apply {
                    hint = getString(if (parameter.required) R.string.workflow_required_field else R.string.workflow_optional_field)
                    contentDescription = parameter.description
                    inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    isSaveEnabled = false
                    filters = arrayOf(InputFilter.LengthFilter(if (number) 11 else 4000))
                }
                fields.addView(input, MATCH_PARENT, WRAP_CONTENT)
                val read: () -> String = { input.text.toString() }
                read
            }
        }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(toolLabel(tool)).setView(scroll(fields))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.workflow_add_step, null).show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val step = WorkflowDraftStep(tool, readValues.map { (key, read) -> key.name to read() }.toMap())
            try {
                MacroCompiler.compile(buildWorkflowJson("Draft", listOf(step)))
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.workflow_invalid_step, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            steps.add(step)
            refreshSteps()
            dialog.dismiss()
        }
    }
    addButton.setOnClickListener {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.workflow_choose_step)
            .setItems(tools.map(::toolLabel).toTypedArray()) { _, position -> configureStep(tools[position]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.workflow_build).setView(scroll(content))
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.workflow_review, null).show()
    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty() || steps.isEmpty()) {
            Toast.makeText(activity, R.string.workflow_name_and_steps, Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        val json = buildWorkflowJson(name, steps)
        if (json.length > 32_768) {
            Toast.makeText(activity, R.string.workflow_size_limit, Toast.LENGTH_LONG).show()
            return@setOnClickListener
        }
        dialog.dismiss()
        onReview(json)
    }
}
