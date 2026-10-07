package com.sendspindroid.ui.wizard

/**
 * Wizard step enum for the Add Server flow.
 *
 *   SS_FindServer -> SS_TestLocal -> SS_Finish
 */
enum class WizardStep {
    SS_FindServer,
    SS_TestLocal,
    SS_Finish
}

/**
 * State of inline connection testing.
 */
sealed class ConnectionTestState {
    data object Idle : ConnectionTestState()
    data object Testing : ConnectionTestState()
    data class Success(val message: String) : ConnectionTestState()
    data class Failed(val error: String) : ConnectionTestState()
}
