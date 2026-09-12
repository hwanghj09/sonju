package com.hwanghj09.sonju

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.InstalledApps
import com.hwanghj09.sonju.agent.UiSnapshot
import com.hwanghj09.sonju.ai.OpenAiPlanner
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InstalledAppsDeviceTest {
    @Test fun catalogContainsLaunchablePackagesVisibleToSonju() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = InstalledApps.query(context.packageManager)
        assertTrue(apps.any { it.packageName == context.packageName })
        assertEquals(apps.size, apps.map { it.packageName }.distinct().size)
        assertTrue(apps.all { context.packageManager.getLaunchIntentForPackage(it.packageName) != null })
        Log.i("SonjuAppsTest", "visible launchable apps=${apps.size}")
    }

    /** Opt in with lookupCommand. Only app metadata and an empty synthetic screen leave the device. */
    @Test fun modelSelectsAnInstalledAppForAnUnnamedLookup() {
        val command = InstrumentationRegistry.getArguments().getString("lookupCommand")
        assumeTrue(!command.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = InstalledApps.query(context.packageManager)
        OpenAiPlanner(installedApps = { apps }).use { planner ->
            val latch = CountDownLatch(1)
            var result: Result<AgentPlan>? = null
            planner.planAsync(requireNotNull(command), UiSnapshot(context.packageName, "손주", epoch = 1,
                elements = emptyList()), null, callback = { result = it; latch.countDown() })
            assertTrue("Model timed out", latch.await(45, TimeUnit.SECONDS))
            val plan = requireNotNull(result).getOrThrow()
            Log.i("SonjuAppsTest", "request=$command goal=${plan.goal} summary=${plan.summary}")
            val action = plan.actions.first()
            assertEquals(ActionType.OPEN_APP, action.type)
            assertFalse(plan.goalCompleted)
            val selected = requireNotNull(apps.singleOrNull { it.packageName == action.target })
            assertNotEquals("Reopening the observation app is not lookup progress", context.packageName, selected.packageName)
            assertEquals(selected.packageName, plan.targetApp)
            Log.i("SonjuAppsTest", "lookup selected=${selected.label} package=${selected.packageName} goalCompleted=false")
        }
    }
}
