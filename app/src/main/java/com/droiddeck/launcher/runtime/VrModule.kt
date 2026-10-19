package com.droiddeck.launcher.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

object VrModule {
    data class State(
        val installed: Boolean = false,
        val present: Boolean = false,
        val busy: Boolean = false,
        val stage: String? = null,
        val percent: Int = -1,
        val error: String? = null,
    )

    const val HAND_MODEL = "models/hand_landmarker.task"
    const val HAND_MODEL_LITE = "models/hand_landmarker_lite.task"
    const val POSE_MODEL = "models/pose_landmarker_lite.task"
    const val LUMA_CALIBRATION = "calibration/luma-ultra-640x480.json"
    private val GUEST = listOf("usr/local/lib/droiddeck-vr", "usr/share/vulkan/implicit_layer.d/droiddeck_vr.json")
    private const val OPENCV = "libopencv_java4.so"
    private const val MEDIAPIPE = "libmediapipe_tasks_jni.so"
    private const val DIR = "vr"
    private const val VERSION = ".version"
    private const val BUNDLED = "vr/vr.tar.zst"
    private const val ARCHIVE = "vr-module-"
    private const val TAG = "VrModule"
    private val REQUIRED = listOf("lib/$OPENCV", "lib/$MEDIAPIPE", HAND_MODEL, HAND_MODEL_LITE, POSE_MODEL, LUMA_CALIBRATION,
        "linuxfs/usr/local/lib/droiddeck-vr/droiddeck/bin/linuxarm64/driver_droiddeck.so",
        "linuxfs/usr/local/lib/droiddeck-vr/layer/libVkLayer_droiddeck_vr.so",
        "linuxfs/usr/share/vulkan/implicit_layer.d/droiddeck_vr.json")

    private val IDLE = setOf(SessionPhase.IDLE, SessionPhase.FAILED)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vr-module").apply { isDaemon = true } }
    private val files = Any()
    private val loaded = HashSet<String>()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        refresh(app)
        worker.execute { listOf("$DIR.new", "$DIR.old").forEach { FileUtils.delete(File(app.filesDir, it)) } }
    }

    fun refresh(context: Context) {
        val present = dir(context).exists()
        val installed = installed(context)
        mutable.update { it.copy(installed = installed, present = present) }
    }

    fun installed(context: Context): Boolean = synchronized(files) { current(dir(context)) }

    fun file(context: Context, relative: String) = File(dir(context), relative)

    fun installedBytes(context: Context): Long = dir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun install(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null): Future<String?> {
        val app = context.applicationContext
        return worker.submit<String?> {
            operate(app, R.string.deskpkg_install_failed) { progress ->
                installNow(app, object : LinuxRuntimeInstaller.ProgressListener {
                    override fun onProgress(stage: String, percent: Int) =
                        onProgress(LinuxRuntimeInstaller.Step.OTHER, stage, percent)

                    override fun onProgress(step: LinuxRuntimeInstaller.Step, stage: String, percent: Int) {
                        progress(stage, percent)
                        listener?.onProgress(step, stage, percent)
                    }
                })
            }
        }
    }

    fun uninstall(context: Context): Future<String?> {
        val app = context.applicationContext
        return worker.submit<String?> { operate(app, R.string.vr_module_remove_failed) { progress -> uninstallNow(app, progress) } }
    }

    fun loadOpenCv(context: Context): Boolean = load(context, OPENCV)

    fun loadMediaPipe(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && load(context, MEDIAPIPE)

    fun stageGuest(context: Context, root: File) = synchronized(files) {
        if (!current(dir(context))) {
            GUEST.forEach { FileUtils.delete(File(root, it)) }
            return@synchronized
        }
        val source = file(context, "linuxfs")
        GUEST.forEach { stage(File(source, it), File(root, it)) }
    }

    private fun current(dir: File): Boolean =
        FileUtils.readString(File(dir, VERSION))?.trim() == BuildConfig.VR_MODULE_TAG && REQUIRED.all { File(dir, it).isFile }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun operate(context: Context, failed: Int, work: ((String, Int) -> Unit) -> String?): String? {
        mutable.update { it.copy(busy = true, stage = null, percent = -1, error = null) }
        var problem: String? = context.getString(failed)
        try {
            problem = work { stage, percent -> mutable.update { it.copy(stage = stage, percent = percent) } }
        } catch (e: Exception) {
            Log.e(TAG, "VR module operation failed", e)
        } finally {
            val present = dir(context).exists()
            mutable.value = State(installed = installed(context), present = present, error = problem)
        }
        return problem
    }

    private fun installNow(context: Context, progress: LinuxRuntimeInstaller.ProgressListener): String? {
        if (installed(context)) return null
        val staging = File(context.filesDir, "$DIR.new")
        val archive = File(context.cacheDir, "$ARCHIVE${BuildConfig.VR_MODULE_TAG}.tar.zst")
        context.cacheDir.listFiles { f -> f.name.startsWith(ARCHIVE) && !f.name.startsWith(archive.name) }?.forEach { it.delete() }
        FileUtils.delete(staging)
        try {
            val downloading = context.getString(R.string.vr_module_downloading)
            progress.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, 0)
            val fetched = if (BuildConfig.VR_MODULE_URL.isEmpty()) FileUtils.copyAsset(context, BUNDLED, archive)
            else Downloader.downloadFile(BuildConfig.VR_MODULE_URL, archive, true, BuildConfig.VR_MODULE_SHA256) { f ->
                progress.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, downloading, if (f < 0) -1 else Math.round(f * 100f))
            }
            if (!fetched) return context.getString(R.string.user_apps_download_failed)
            progress.onProgress(LinuxRuntimeInstaller.Step.VERIFYING, context.getString(R.string.rtinst_verifying), -1)
            if (!BuildConfig.VR_MODULE_SHA256.equals(Hashes.sha256(archive), ignoreCase = true)) {
                archive.delete()
                return context.getString(R.string.deskpkg_checksum_mismatch)
            }
            progress.onProgress(LinuxRuntimeInstaller.Step.OTHER, context.getString(R.string.rtinst_extracting), -1)
            if (!LinuxRuntimeInstaller.extract(context, archive, staging, null) ||
                !FileUtils.writeString(File(staging, VERSION), BuildConfig.VR_MODULE_TAG) || !current(staging)) {
                return context.getString(R.string.deskpkg_extract_failed)
            }
            archive.delete()
            if (!swap(context, staging)) return context.getString(R.string.deskpkg_install_failed)
            if (SessionState.running || SessionState.phase !in IDLE) stageGuest(context, LinuxRuntime.rootDir(context))
            return null
        } finally {
            FileUtils.delete(staging)
        }
    }

    private fun swap(context: Context, staging: File): Boolean = synchronized(files) {
        val dir = dir(context)
        val old = File(context.filesDir, "$DIR.old")
        FileUtils.delete(old)
        if (dir.exists() && !dir.renameTo(old)) return@synchronized false
        if (!staging.renameTo(dir)) {
            old.renameTo(dir)
            return@synchronized false
        }
        FileUtils.delete(old)
        true
    }

    private fun uninstallNow(context: Context, progress: (String, Int) -> Unit): String? {
        val removing = context.getString(R.string.vr_module_removing)
        val maintenance = LinuxRuntimeInstaller.beginMaintenance(context, removing)
            ?: return context.getString(R.string.vr_module_busy)
        val removed = maintenance.run({ stage, percent -> progress(stage, percent) }) { listener ->
            listener.onProgress(removing, -1)
            synchronized(files) {
                val old = File(context.filesDir, "$DIR.old")
                FileUtils.delete(old)
                val dir = dir(context)
                if (dir.exists() && !dir.renameTo(old)) return@run context.getString(R.string.vr_module_remove_failed)
                GUEST.forEach { FileUtils.delete(File(LinuxRuntime.rootDir(context), it)) }
                FileUtils.delete(old)
            }
            null
        }
        return if (removed) null else LinuxRuntimeInstaller.removalError()
    }

    private fun load(context: Context, library: String): Boolean = synchronized(loaded) {
        if (library in loaded) return@synchronized true
        if (!installed(context)) return@synchronized false
        runCatching { System.load(file(context, "lib/$library").absolutePath) }
            .onFailure { Log.w(TAG, "$library could not load", it) }
            .isSuccess.also { if (it) loaded += library }
    }

    private fun stage(source: File, target: File) {
        if (source.isDirectory) {
            val names = source.list().orEmpty().toSet()
            names.forEach { stage(File(source, it), File(target, it)) }
            target.list()?.filter { it !in names }?.forEach { FileUtils.delete(File(target, it)) }
            return
        }
        val staged = File(target.parentFile, target.name + ".staged")
        var installed = false
        try {
            target.parentFile?.mkdirs()
            source.inputStream().use { input -> staged.outputStream().use { output -> FileUtils.copy(input, output) } }
            installed = (!source.canExecute() || staged.setExecutable(true, false)) && staged.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "could not stage ${target.name}", e)
        } finally {
            if (!installed) staged.delete()
        }
    }
}
