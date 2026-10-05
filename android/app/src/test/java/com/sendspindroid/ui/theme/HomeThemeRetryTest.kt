package com.sendspindroid.ui.theme

import com.sendspindroid.musicassistant.transport.MaApiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** HW-77: home_theme/get answered "Invalid command" while MA's provider is still loading. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeThemeRetryTest {
    private val c = HomeThemeController
    private val dispatcher = StandardTestDispatcher()
    private var calls = 0
    private val dusk = Json.parseToJsonElement("""{"claim":{"theme":"dusk","mode":"dark"}}""").jsonObject

    private fun answer(invalidTimes: Int): suspend () -> Result<JsonObject> = {
        calls++
        if (calls <= invalidTimes) {
            Result.failure(MaApiTransport.MaCommandException("12", "Invalid command: home_theme/get"))
        } else {
            Result.success(dusk)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        c.isReady = { true }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    @Test
    fun retriesUntilTheProviderIsLoaded() = runTest(dispatcher) {
        c.getHomeTheme = answer(invalidTimes = 2)
        c.refresh()
        settle()
        assertEquals(3, calls)
        assertEquals(HomeThemeController.Effective("dusk", "dark", false), c.effective.value)
    }

    @Test
    fun givesUpAfterThreeRetries() = runTest(dispatcher) {
        c.getHomeTheme = answer(invalidTimes = Int.MAX_VALUE)
        c.refresh()
        settle()
        assertEquals(4, calls)
    }

    @Test
    fun otherErrorsAreNotRetried() = runTest(dispatcher) {
        c.getHomeTheme = { calls++; Result.failure(MaApiTransport.MaCommandException("999", "boom")) }
        c.refresh()
        settle()
        assertEquals(1, calls)
    }

    @Test
    fun theNextRefreshCancelsThePendingRetry() = runTest(dispatcher) {
        c.getHomeTheme = answer(invalidTimes = 1)
        c.refresh()
        advanceTimeBy(1_000)
        launch { c.refresh() }
        settle()
        assertEquals(2, calls)
    }
}
