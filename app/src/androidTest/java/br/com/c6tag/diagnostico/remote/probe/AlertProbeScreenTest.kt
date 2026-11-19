package br.com.c6tag.diagnostico.remote.probe

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.monitor.MonitorActions
import br.com.c6tag.diagnostico.ui.monitor.MonitorSettingsScreen
import br.com.c6tag.diagnostico.ui.monitor.MonitorUiState
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AlertProbeScreenTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext
    private val platform = FakePlatform()
    private val factoryCalls = AtomicInteger()
    private val openedActivities = CopyOnWriteArrayList<Activity>()
    private val resumedActivities = CopyOnWriteArrayList<Activity>()
    private val preferencesName = "probe-ui-test-${UUID.randomUUID()}"
    private val preferences by lazy {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }
    private val store by lazy { ProbeSessionStore(context, preferences) }
    private lateinit var previousFactory: (Context) -> ProbeNotificationController

    private val factoryRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    previousFactory = ProbeDependencies.controllerFactory
                    ProbeDependencies.controllerFactory = { activityContext ->
                        factoryCalls.incrementAndGet()
                        ProbeNotificationController(activityContext, store, platform)
                    }
                    val callbacks = object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityCreated(activity: Activity, state: Bundle?) {
                            if (activity is AlertProbeActivity) openedActivities += activity
                        }

                        override fun onActivityStarted(activity: Activity) = Unit
                        override fun onActivityResumed(activity: Activity) {
                            resumedActivities += activity
                        }
                        override fun onActivityPaused(activity: Activity) {
                            resumedActivities -= activity
                        }
                        override fun onActivityStopped(activity: Activity) = Unit
                        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                        override fun onActivityDestroyed(activity: Activity) = Unit
                    }
                    val application = context.applicationContext as Application
                    application.registerActivityLifecycleCallbacks(callbacks)
                    try {
                        base.evaluate()
                    } finally {
                        instrumentation.runOnMainSync {
                            openedActivities.forEach { if (!it.isFinishing) it.finish() }
                        }
                        application.unregisterActivityLifecycleCallbacks(callbacks)
                        ProbeDependencies.controllerFactory = previousFactory
                        preferences.edit().clear().commit()
                    }
                }
            }
    }

    private val compose = createAndroidComposeRule<AlertProbeActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(factoryRule).around(compose)

    @Test
    fun openingTheActualSettingsEntryShowsTheProbeActivityWithoutPublishing() {
        val monitor = addProbeActivityMonitor()
        try {
            showActualSettings()
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("probe-open"))
            compose.onNodeWithTag("probe-open")
                .assertHasClickAction().performClick()
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertNotNull("The settings entry must launch the real debug activity", activity)
            assertTrue("The settings entry must launch AlertProbeActivity", activity is AlertProbeActivity)
            assertTrue("Both Activity creations must use the installed fake controller factory", factoryCalls.get() >= 2)
            assertTrue("Opening the probe must remain read-only", platform.published.isEmpty())
            assertTrue("Opening the probe must not create a channel", platform.createdChannels.isEmpty())
            assertEquals(0, platform.cancelAttempts.get())

            val settingsActivity = compose.activity
            instrumentation.runOnMainSync {
                (activity as AlertProbeActivity).onBackPressedDispatcher.onBackPressed()
            }
            compose.waitUntil(5_000) { settingsActivity in resumedActivities }
            compose.onNodeWithTag("settings-scroll").assertIsDisplayed()
        } finally {
            closeProbeActivities(monitor)
        }
    }

    @Test
    fun onlyExplicitStartPublishesAndStopCancelsTheRegisteredOccurrence() {
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.createdChannels.isEmpty())

        tapText("Iniciar teste")
        compose.waitUntil(5_000) { platform.published.size == 1 }
        val occurrenceId = store.currentOccurrenceId()
        assertNotNull(occurrenceId)
        assertEquals(1, platform.createdChannels.size)

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Teste de alerta crítico").assertIsDisplayed()
        assertEquals("Reopening must not start another test", 1, platform.published.size)
        assertEquals(occurrenceId, store.currentOccurrenceId())
        compose.onNodeWithText("Parar som").assertIsDisplayed()

        tapText("Parar som")
        compose.waitUntil(5_000) { store.currentOccurrenceId() == null }
        assertEquals(listOf(ProbeNotificationController.NOTIFICATION_ID), platform.cancelledIds.toList())
        assertEquals("Stop must not submit another notification", 1, platform.published.size)
    }

    @Test
    fun failedPublicationAndCancellationKeepStopVisibleForRetry() {
        platform.publishFailure = IllegalStateException("synthetic publish failure")
        platform.cancelFailure = IllegalStateException("synthetic cancellation failure")

        tapText("Iniciar teste")
        compose.waitUntil(5_000) { store.currentOccurrenceId() != null }
        val occurrenceId = requireNotNull(store.currentOccurrenceId())
        compose.onNodeWithText("Parar som").assertIsDisplayed()
        assertTrue(platform.published.isEmpty())

        tapText("Parar som")
        assertEquals("A failed cancel must retain the occurrence for retry", occurrenceId, store.currentOccurrenceId())
        compose.onNodeWithText("Parar som").assertIsDisplayed()

        platform.cancelFailure = null
        tapText("Parar som")
        compose.waitUntil(5_000) { store.currentOccurrenceId() == null }
        assertEquals(3, platform.cancelAttempts.get())
        assertEquals(0, platform.published.size)
    }

    @Test
    fun deniedAndUnknownCapabilitiesRemainReadableWithoutAutomaticSystemUi() {
        platform.permission = false
        platform.appNotificationsEnabled = false
        platform.channelFailure = IllegalStateException("synthetic channel read failure")
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Permissão de notificações: não concedida.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notificações do app: desativadas.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Não foi possível consultar o canal de notificações.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Para receber a notificação de teste, você pode conceder POST_NOTIFICATIONS. A permissão só será solicitada depois que você tocar no botão abaixo.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("probe-permission").performScrollTo().assertIsDisplayed()
        assertTrue(platform.published.isEmpty())
        assertTrue(platform.createdChannels.isEmpty())
        assertEquals(0, platform.cancelAttempts.get())
    }

    @Test
    fun blockedChannelIsVisibleDisablesStartAndKeepsExplicitSettingsActionAvailable() {
        val blockedChannel = NotificationChannel(
            ProbeNotificationController.CHANNEL_ID,
            "synthetic blocked probe channel",
            android.app.NotificationManager.IMPORTANCE_NONE,
        )
        platform.channelValue = blockedChannel
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Permissão de notificações: concedida.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Notificações do app: ativadas.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Canal de notificações bloqueado no Android.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("probe-start").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("probe-settings")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        assertTrue("Opening/recreating must not publish", platform.published.isEmpty())
        assertTrue("Opening/recreating must not create or reset the blocked channel", platform.createdChannels.isEmpty())
        assertEquals("Opening/recreating must not cancel", 0, platform.cancelAttempts.get())
        assertSame(blockedChannel, platform.channelValue)

        var settingsRequests = 0
        compose.activity.setContent {
            MonitorTheme {
                AlertProbeScreen(
                    state = AlertProbeScreenState(
                        status = ProbeControllerStatus(
                            permission = ProbeCapabilityStatus.GRANTED,
                            appNotifications = ProbeCapabilityStatus.GRANTED,
                            channel = ProbeChannelStatus.BLOCKED,
                            storedOccurrenceId = null,
                        ),
                    ),
                    actions = AlertProbeScreenActions(
                        onStart = {},
                        onStop = {},
                        onRequestPermission = {},
                        onOpenSettings = { settingsRequests++ },
                        onBack = {},
                    ),
                )
            }
        }
        compose.onNodeWithTag("probe-settings").performScrollTo().performClick()

        assertEquals("The explicit settings action must call the fake callback", 1, settingsRequests)
        assertTrue("The component callback must not publish", platform.published.isEmpty())
        assertTrue("The component callback must not create or reset the channel", platform.createdChannels.isEmpty())
        assertEquals("The component callback must not cancel", 0, platform.cancelAttempts.get())
        assertSame(blockedChannel, platform.channelValue)
    }

    @Test
    fun screenKeepsAccessibleActionsAcrossThemesAndLargeText() {
        val preference = mutableStateOf(ThemePreference.LIGHT)
        val fontScale = mutableStateOf(1f)
        var permissionRequests = 0
        var settingsRequests = 0
        var stops = 0
        var backs = 0
        val state = AlertProbeScreenState(
            status = ProbeControllerStatus(
                permission = ProbeCapabilityStatus.DENIED,
                appNotifications = ProbeCapabilityStatus.UNKNOWN,
                channel = ProbeChannelStatus.UNKNOWN,
                storedOccurrenceId = UUID.randomUUID().toString(),
            ),
            showPermissionAction = true,
        )
        compose.activity.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale.value),
            ) {
                MonitorTheme(preference.value) {
                    AlertProbeScreen(
                        state = state,
                        actions = AlertProbeScreenActions(
                            onStart = {},
                            onStop = { stops++ },
                            onRequestPermission = { permissionRequests++ },
                            onOpenSettings = { settingsRequests++ },
                            onBack = { backs++ },
                        ),
                    )
                }
            }
        }

        for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle {
                    preference.value = theme
                    fontScale.value = scale
                }
                compose.onNodeWithTag("probe-screen-title")
                    .performScrollTo()
                    .assertIsDisplayed()
                compose.onNodeWithText("Dados sintéticos — não é seu saldo")
                    .performScrollTo()
                    .assertIsDisplayed()
                compose.onNodeWithTag("probe-start").assertIsNotEnabled()
                compose.onNodeWithTag("probe-stop")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("probe-permission")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                compose.onNodeWithTag("probe-settings")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                compose.onNodeWithTag("probe-back")
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                compose.onNodeWithText("Permissão de notificações: não concedida.")
                    .performScrollTo()
                    .assertIsDisplayed()
                compose.onNodeWithText("Não foi possível consultar o canal de notificações.")
                    .performScrollTo()
                    .assertIsDisplayed()
            }
        }

        compose.onNodeWithTag("probe-stop").performScrollTo().performClick()
        compose.onNodeWithTag("probe-permission").performScrollTo().performClick()
        compose.onNodeWithTag("probe-settings").performScrollTo().performClick()
        compose.onNodeWithTag("probe-back").performScrollTo().performClick()
        assertEquals(1, stops)
        assertEquals(1, permissionRequests)
        assertEquals(1, settingsRequests)
        assertEquals(1, backs)
        compose.onNodeWithText("teste passou", ignoreCase = true).assertIsNotDisplayed()
    }

    private fun showActualSettings() {
        compose.activity.setContent {
            MonitorTheme {
                MonitorSettingsScreen(
                    state = MonitorUiState(isLoading = false),
                    actions = MonitorActions(),
                )
            }
        }
    }

    private fun addProbeActivityMonitor(): Instrumentation.ActivityMonitor =
        Instrumentation.ActivityMonitor(AlertProbeActivity::class.java.name, null, false).also {
            instrumentation.addMonitor(it)
        }

    private fun tapText(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun closeProbeActivities(monitor: Instrumentation.ActivityMonitor) {
        instrumentation.removeMonitor(monitor)
        instrumentation.runOnMainSync {
            openedActivities.forEach { if (!it.isFinishing) it.finish() }
        }
    }

    private class FakePlatform : ProbeNotificationPlatform {
        @Volatile var permission = true
        @Volatile var appNotificationsEnabled = true
        @Volatile var channelValue: NotificationChannel? = null
        @Volatile var permissionFailure: Exception? = null
        @Volatile var appNotificationsFailure: Exception? = null
        @Volatile var channelFailure: Exception? = null
        @Volatile var publishFailure: Exception? = null
        @Volatile var cancelFailure: Exception? = null
        val published = CopyOnWriteArrayList<Pair<Int, Notification>>()
        val createdChannels = CopyOnWriteArrayList<NotificationChannel>()
        val cancelAttempts = AtomicInteger()
        val cancelledIds = CopyOnWriteArrayList<Int>()

        override fun permissionGranted(): Boolean {
            permissionFailure?.let { throw it }
            return permission
        }
        override fun notificationsEnabled(): Boolean {
            appNotificationsFailure?.let { throw it }
            return appNotificationsEnabled
        }
        override fun channel(): NotificationChannel? {
            channelFailure?.let { throw it }
            return channelValue
        }
        override fun createChannel(channel: NotificationChannel) {
            createdChannels += channel
            channelValue = channel
        }
        override fun publish(notificationId: Int, notification: Notification) {
            publishFailure?.let { throw it }
            published += notificationId to notification
        }
        override fun cancel(notificationId: Int) {
            cancelAttempts.incrementAndGet()
            cancelFailure?.let { throw it }
            cancelledIds += notificationId
        }
    }
}
