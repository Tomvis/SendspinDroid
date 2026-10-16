package com.sendspindroid.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Holds a text field's keyboard back on a TV until OK is pressed on it.
 *
 * A text field opens the keyboard as soon as it takes focus, and on a TV the
 * D-pad passes focus through a field on its way to other controls, or a
 * dialog starts on its field, so the keyboard would cover the screen each
 * time. KeyboardOptions has a
 * showKeyboardOnFocus switch for this, but the text fields that take a String
 * value do not honour it. A read-only field opens no keyboard, so there the
 * field is read-only until OK is pressed on it, and again once focus leaves.
 *
 * Up and Down also leave the field there. A text field passes them on to
 * focus only when they come from a D-pad it takes for real hardware, which
 * leaves out keys injected the way remote apps can send them.
 *
 * For a single-line field. Pass [readOnly] to the field and add [modifier] to
 * its modifier. On other form factors the field is never read-only and the
 * modifier does nothing.
 */
@Stable
class TvKeyboardGate internal constructor(isTv: Boolean, focusManager: FocusManager) {

    var readOnly by mutableStateOf(isTv)
        private set

    val modifier: Modifier = if (!isTv) Modifier else Modifier
        .onFocusChanged { if (!it.isFocused) readOnly = true }
        // Seen before the field itself, which then opens the keyboard as it
        // turns editable.
        .onPreviewKeyEvent { event ->
            val down = event.type == KeyEventType.KeyDown
            if (down && event.key in OkKeys) readOnly = false
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                else -> return@onPreviewKeyEvent false
            }
            if (down) focusManager.moveFocus(direction)
            true
        }
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

@Composable
fun rememberTvKeyboardGate(): TvKeyboardGate {
    val isTv = LocalFormFactor.current == FormFactor.TV
    val focusManager = LocalFocusManager.current
    return remember(isTv, focusManager) { TvKeyboardGate(isTv, focusManager) }
}
