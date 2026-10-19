package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VrModuleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val module get() = File(context.filesDir, "vr")
    private val root get() = LinuxRuntime.rootDir(context)
    private val driver = "usr/local/lib/droiddeck-vr/droiddeck/bin/linuxarm64/driver_droiddeck.so"
    private val layer = "usr/local/lib/droiddeck-vr/layer/libVkLayer_droiddeck_vr.so"
    private val manifest = "usr/share/vulkan/implicit_layer.d/droiddeck_vr.json"

    @Before fun idle() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
    }

    @After fun clean() {
        FileUtils.delete(module)
        FileUtils.delete(root)
        VrModule.refresh(context)
    }

    private fun install(tag: String = BuildConfig.VR_MODULE_TAG) {
        listOf("lib/libopencv_java4.so", "lib/libmediapipe_tasks_jni.so", VrModule.HAND_MODEL, VrModule.HAND_MODEL_LITE,
            VrModule.POSE_MODEL, VrModule.LUMA_CALIBRATION, "linuxfs/$driver", "linuxfs/$layer", "linuxfs/$manifest")
            .forEach { File(module, it).apply { parentFile!!.mkdirs(); writeText(it) } }
        File(module, "linuxfs/$driver").setExecutable(true, false)
        File(module, ".version").writeText(tag)
        VrModule.refresh(context)
    }

    private fun guest(relative: String) = File(root, relative)

    @Test fun onlyTheCurrentCompleteModuleCounts() {
        assertFalse(VrModule.installed(context))
        install("vr-r0")
        assertFalse(VrModule.installed(context))
        assertTrue(VrModule.state.value.present)
        install()
        assertTrue(VrModule.installed(context))
        assertTrue(VrModule.state.value.installed)
        File(module, VrModule.POSE_MODEL).delete()
        assertFalse(VrModule.installed(context))
    }

    @Test fun stagingCopiesTheGuestFilesAndPrunesOldOnes() {
        install()
        guest("usr/local/lib/droiddeck-vr/stale.so").apply { parentFile!!.mkdirs(); writeText("old") }
        guest("usr/local/lib/other/keep").apply { parentFile!!.mkdirs(); writeText("keep") }
        VrModule.stageGuest(context, root)
        assertEquals("linuxfs/$driver", guest(driver).readText())
        assertTrue(guest(driver).canExecute())
        assertEquals("linuxfs/$manifest", guest(manifest).readText())
        assertFalse(guest("usr/local/lib/droiddeck-vr/stale.so").exists())
        assertEquals("keep", guest("usr/local/lib/other/keep").readText())
        assertFalse(guest("$driver.staged").exists())
    }

    @Test fun stagingWithoutTheModuleRemovesFilesAnOlderAppLeft() {
        guest(layer).apply { parentFile!!.mkdirs(); writeText("bundled") }
        guest(manifest).apply { parentFile!!.mkdirs(); writeText("bundled") }
        guest("usr/share/vulkan/implicit_layer.d/other.json").writeText("keep")
        VrModule.stageGuest(context, root)
        assertFalse(guest("usr/local/lib/droiddeck-vr").exists())
        assertFalse(guest(manifest).exists())
        assertTrue(guest("usr/share/vulkan/implicit_layer.d/other.json").exists())
    }

    @Test(timeout = 15000) fun uninstallWaitsForTheSessionThenRemovesEverything() {
        install()
        VrModule.stageGuest(context, root)
        SessionState.running = true
        assertEquals(context.getString(R.string.vr_module_busy), VrModule.uninstall(context).get(10, TimeUnit.SECONDS))
        assertTrue(VrModule.state.value.installed)
        assertTrue(guest(driver).exists())
        SessionState.running = false
        assertNull(VrModule.uninstall(context).get(10, TimeUnit.SECONDS))
        assertFalse(module.exists())
        assertFalse(guest("usr/local/lib/droiddeck-vr").exists())
        assertFalse(guest(manifest).exists())
        assertEquals(VrModule.State(), VrModule.state.value)
        assertFalse(LinuxRuntimeInstaller.isBusy())
    }

    @Test fun librariesDoNotLoadWithoutTheModule() {
        assertFalse(VrModule.loadOpenCv(context))
        assertFalse(VrModule.loadMediaPipe(context))
    }
}
