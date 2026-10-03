package com.assistant.core.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import com.assistant.core.design.Corner
import com.assistant.core.design.icons.ArchieIcon
import com.assistant.core.design.theme.ArchieTheme

/** Outlined (settings forms) or filled (dense values like the wake phrase). */
enum class TextFieldStyle { Outlined, Filled }

/**
 * M3 text field, 56 dp, 16 sp: floating label, one-line supporting text, error state with the
 * error text in place of the supporting text (mockup `.tf`, `.sup`).
 */
@Composable
fun ArchieTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    style: TextFieldStyle = TextFieldStyle.Outlined,
    supportingText: String? = null,
    errorText: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = ArchieTheme.colors
    val isError = errorText != null
    val support: (@Composable () -> Unit)? = (errorText ?: supportingText)?.let { { Text(it) } }
    val lead: (@Composable () -> Unit)? = leadingIcon?.let { { ArchieIcon(it, null) } }
    val labelSlot: @Composable () -> Unit = { Text(label) }
    val placeholderSlot: (@Composable () -> Unit)? = placeholder?.let { { Text(it) } }
    val textStyle = ArchieTheme.typography.bodyLarge
    when (style) {
        TextFieldStyle.Outlined -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = textStyle,
            label = labelSlot,
            placeholder = placeholderSlot,
            leadingIcon = lead,
            trailingIcon = trailing,
            supportingText = support,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            interactionSource = interactionSource,
            shape = RoundedCornerShape(Corner.ExtraSmall),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary,
                unfocusedBorderColor = c.outline,
                focusedLabelColor = c.primary,
                unfocusedLabelColor = c.onSurfaceVariant,
                cursorColor = c.primary,
                errorBorderColor = c.error,
                errorLabelColor = c.error,
                errorSupportingTextColor = c.error,
                focusedSupportingTextColor = c.onSurfaceVariant,
                unfocusedSupportingTextColor = c.onSurfaceVariant,
            ),
        )
        TextFieldStyle.Filled -> TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = textStyle,
            label = labelSlot,
            placeholder = placeholderSlot,
            leadingIcon = lead,
            trailingIcon = trailing,
            supportingText = support,
            isError = isError,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            interactionSource = interactionSource,
            shape = RoundedCornerShape(topStart = Corner.ExtraSmall, topEnd = Corner.ExtraSmall),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surfaceContainerHighest,
                unfocusedContainerColor = c.surfaceContainerHighest,
                errorContainerColor = c.surfaceContainerHighest,
                focusedIndicatorColor = c.primary,
                unfocusedIndicatorColor = c.onSurfaceVariant,
                focusedLabelColor = c.primary,
                unfocusedLabelColor = c.onSurfaceVariant,
                cursorColor = c.primary,
            ),
        )
    }
}
