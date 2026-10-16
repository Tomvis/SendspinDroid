package com.sendspindroid.ui.main.components

import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Up and Down move focus off a horizontal material3 Slider, which from
 * material3 1.4 steps its value on all four arrow keys: without this, a
 * keyboard or D-pad that lands on the slider can leave it only with Tab.
 * Left and Right still step it. Add it to the Slider's modifier.
 *
 * When Compose has nothing further that way, focus goes on to a View that
 * way, such as a Snackbar's action, searched for from [view] (the slider's
 * LocalView). The View system would not do it itself: the key never gets
 * past the Slider.
 */
internal fun Modifier.upDownMovesFocus(focusManager: FocusManager, view: View): Modifier =
    onPreviewKeyEvent { event ->
        val (direction, viewDirection) = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up to View.FOCUS_UP
            Key.DirectionDown -> FocusDirection.Down to View.FOCUS_DOWN
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown && !focusManager.moveFocus(direction)) {
            view.focusSearch(viewDirection)?.requestFocus(viewDirection)
        }
        true
    }
