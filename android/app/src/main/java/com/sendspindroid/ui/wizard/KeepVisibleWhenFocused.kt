package com.sendspindroid.ui.wizard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Scrolls a text field back into view while it has focus and the keyboard is
 * moving. In landscape the keyboard leaves only a strip of the form showing,
 * and without this the field being typed into can end up outside it.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.keepVisibleWhenFocused(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboardHeight) {
        if (focused) requester.bringIntoView()
    }
    bringIntoViewRequester(requester).onFocusChanged { focused = it.isFocused }
}
