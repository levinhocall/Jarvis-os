package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Bundled offline model, unpacked atomically and loaded only for explicit audio features. */
object VoskModelManager {
    private val ready = MutableStateFlow(false)
    val isModelReady = ready.asStateFlow()
    private val speakerReady = MutableStateFlow(false)
    val isSpeakerModelReady = speakerReady.asStateFlow()
    private val state = MutableStateFlow("Bundled offline model · not loaded")
    val status = state.asStateFlow()
    @Volatile var model: Model? = null; private set
    @Volatile var speakerModel: SpeakerModel? = null; private set
    private val initializing = AtomicBoolean(false)

    fun init(context: Context, scope: CoroutineScope, includeSpeaker: Boolean = true) {
        if (model != null && (!includeSpeaker || speakerModel != null)) return
        if (!initializing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                if (model == null) {
                    state.value = "Preparing bundled offline speech model…"
                    val folder = unpack(context, "model-en-us", "vosk-model", listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst"))
                    model = Model(folder.absolutePath)
                    ready.value = true
                }
                if (includeSpeaker && speakerModel == null) {
                    val folder = unpack(context, "model-spk", "vosk-spk-model", listOf("final.ext.raw"))
                    speakerModel = SpeakerModel(folder.absolutePath)
                    speakerReady.value = true
                }
                state.value = "Offline Vosk model ready"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.value = "Offline model could not load (${error.javaClass.simpleName}). Retry from Wake Word." }
            catch (_: OutOfMemoryError) { state.value = "Not enough free memory for offline speech. Close other apps and retry." }
            catch (error: LinkageError) { state.value = "Offline speech native library unavailable on this device (${error.javaClass.simpleName})." }
            finally { initializing.set(false) }
        }
    }
    private fun unpack(context: Context, asset: String, name: String, required: List<String>): File {
        val target = File(context.filesDir, name)
        if (File(target, ".jarvis-complete").isFile && required.all { File(target, it).let { f -> f.isFile && f.length() > 0 } }) return target
        val staging = File(context.filesDir, "$name.staging")
        staging.deleteRecursively(); check(staging.mkdirs())
        try {
            copy(context.assets, asset, staging)
            check(required.all { File(staging, it).length() > 0 }) { "Incomplete model assets" }
            File(staging, ".jarvis-complete").writeText("bundled-v1")
            check(!target.exists() || target.deleteRecursively())
            check(staging.renameTo(target)) { "Model installation could not complete" }
            return target
        } finally { staging.deleteRecursively() }
    }
    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copy(assets, "$path/$it", File(target, it)) }
        }
    }
}
