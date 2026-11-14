package io.github.randomf.safedriveway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.randomf.safedriveway.backend.Backend
import io.github.randomf.safedriveway.backend.UnlockResult
import io.github.randomf.safedriveway.passage.Limits
import io.github.randomf.safedriveway.passage.PassageId
import io.github.randomf.safedriveway.passage.isValidPin
import io.github.randomf.safedriveway.resources.Res
import io.github.randomf.safedriveway.resources.cancel
import io.github.randomf.safedriveway.resources.forgot_pin
import io.github.randomf.safedriveway.resources.new_pin
import io.github.randomf.safedriveway.resources.pin
import io.github.randomf.safedriveway.resources.pin_digits
import io.github.randomf.safedriveway.resources.pin_locked_out
import io.github.randomf.safedriveway.resources.pin_mismatch
import io.github.randomf.safedriveway.resources.pin_prompt
import io.github.randomf.safedriveway.resources.pin_title
import io.github.randomf.safedriveway.resources.pin_wrong
import io.github.randomf.safedriveway.resources.repeat_pin
import io.github.randomf.safedriveway.resources.unlock
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Asks for the passage's PIN before its setup is changed. A correct PIN is remembered. */
@Composable
fun PinDialog(
    backend: Backend,
    passage: PassageId,
    passageName: String,
    onUnlocked: () -> Unit,
    onForgot: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UnlockResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.pin_prompt, passageName))
                PinField(pin, { pin = it }, stringResource(Res.string.pin))
                when (result) {
                    UnlockResult.WrongPin -> ErrorText(stringResource(Res.string.pin_wrong))
                    UnlockResult.LockedOut -> ErrorText(stringResource(Res.string.pin_locked_out))
                    else -> {}
                }
                TextButton(onClick = onForgot) { Text(stringResource(Res.string.forgot_pin)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        result = backend.unlock(passage, pin)
                        busy = false
                        if (result == UnlockResult.Unlocked) onUnlocked()
                    }
                },
                enabled = isValidPin(pin) && !busy
            ) { Text(stringResource(Res.string.unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        }
    )
}

/** A new PIN, typed twice. [onChange] gets the PIN once both match and it's valid, else null. */
@Composable
fun NewPinFields(onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    fun update() = onChange(first.takeIf { isValidPin(it) && it == second })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PinField(first, {
            first = it
            update()
        }, stringResource(Res.string.new_pin))
        PinField(second, {
            second = it
            update()
        }, stringResource(Res.string.repeat_pin))
        Hint(stringResource(Res.string.pin_digits, Limits.PIN_MIN_DIGITS, Limits.PIN_MAX_DIGITS))
        if (second.length >= first.length && second.isNotEmpty() && first != second) {
            ErrorText(stringResource(Res.string.pin_mismatch))
        }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(Limits.PIN_MAX_DIGITS)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}
