package com.sendspindroid.ui.server

import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A failed connection test returns to the address with its error, which lasts
 * until the address changes. Back during a test cancels it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerWizardViewModelTest {

    // The test runs in viewModelScope, on the main dispatcher
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun wizard(): AddServerWizardViewModel = AddServerWizardViewModel().apply {
        localAddress = "192.168.1.100:8927"
    }

    private fun failedTest(): AddServerWizardViewModel = wizard().apply {
        startLocalTest { Result.failure(IOException("Connection timed out")) }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `a test that connects moves on to Finish`() {
        val viewModel = wizard()
        var tested: String? = null

        viewModel.startLocalTest { address ->
            tested = address
            Result.success(101)
        }
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals("192.168.1.100:8927", tested)
        assertEquals(WizardStep.SS_Finish, viewModel.currentStep.value)
    }

    @Test
    fun `a failed test returns to the address with its error`() {
        val viewModel = failedTest()

        assertEquals(WizardStep.SS_FindServer, viewModel.currentStep.value)
        assertEquals(ConnectionTestState.Failed("Connection timed out"), viewModel.localTestState.value)
        assertEquals("192.168.1.100:8927", viewModel.localAddress)
    }

    @Test
    fun `editing the address clears the error`() {
        val viewModel = failedTest()

        viewModel.handleStepAction(WizardStepAction.UpdateLocalAddress("192.168.1.10:8927"))

        assertEquals(ConnectionTestState.Idle, viewModel.localTestState.value)
    }

    @Test
    fun `picking a discovered server clears the error`() {
        val viewModel = failedTest()

        viewModel.handleStepAction(
            WizardStepAction.SelectDiscoveredServer(DiscoveredServerUi("1", "Office", "192.168.1.101:8927"))
        )

        assertEquals(ConnectionTestState.Idle, viewModel.localTestState.value)
    }

    @Test
    fun `Back from the testing step returns to the address and keeps it`() {
        val viewModel = AddServerWizardViewModel().apply {
            localAddress = "192.168.1.100:8927"
            navigateTo(WizardStep.SS_TestLocal)
        }

        assertEquals(WizardStep.SS_FindServer, viewModel.onBack())
        assertEquals("192.168.1.100:8927", viewModel.localAddress)
    }

    @Test
    fun `Back during the test cancels it, so its error never turns up`() {
        val viewModel = wizard()
        val result = CompletableDeferred<Result<Int>>()
        viewModel.startLocalTest { result.await() }
        mainDispatcher.scheduler.advanceUntilIdle()

        viewModel.onBack()
        result.complete(Result.failure(IOException("Connection timed out")))
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WizardStep.SS_FindServer, viewModel.currentStep.value)
        assertEquals(ConnectionTestState.Idle, viewModel.localTestState.value)
    }

    @Test
    fun `a test cancelled by Back does not move the next one on to Finish`() {
        val viewModel = wizard()
        val first = CompletableDeferred<Result<Int>>()
        viewModel.startLocalTest { first.await() }
        mainDispatcher.scheduler.advanceUntilIdle()
        viewModel.onBack()

        viewModel.startLocalTest { CompletableDeferred<Result<Int>>().await() }
        first.complete(Result.success(101))
        mainDispatcher.scheduler.advanceUntilIdle()

        assertEquals(WizardStep.SS_TestLocal, viewModel.currentStep.value)
    }
}
