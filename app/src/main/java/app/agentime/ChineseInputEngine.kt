package app.agentime

import android.content.Context
import android.util.Log
import java.io.File

/** Boundary between the keyboard UI and the Chinese composition engine. */
interface ChineseInputEngine {
    fun pinyinCandidates(composing: String): List<String>
    fun t9Candidates(digits: String): List<String>
    fun commitPinyin(composing: String): String?

    /** Optional hook: engines that need on-disk data can prepare it here. */
    fun prepare(context: Context) {}
}

/**
 * Narrow contract implemented by the JNI wrapper around librime.
 *
 * Keeping the native contract here means the keyboard does not depend on a
 * particular JNI project layout. The native library only needs to expose
 * these three operations and can keep its own Rime session/schema state.
 */
private interface RimeBridge {
    fun initialize(sharedDataDir: String, userDataDir: String): Boolean
    fun isReady(): Boolean
    fun rimeVersion(): String?
    fun pinyinCandidates(composing: String): List<String>
    fun t9Candidates(digits: String): List<String>
    fun commitPinyin(composing: String): String?
}

/** JNI entry point for the librime Android build (see src/main/cpp). */
private class RimeJniBridge private constructor() : RimeBridge {
    override external fun initialize(sharedDataDir: String, userDataDir: String): Boolean
    override external fun isReady(): Boolean
    override external fun rimeVersion(): String?
    override external fun pinyinCandidates(composing: String): List<String>
    override external fun t9Candidates(digits: String): List<String>
    override external fun commitPinyin(composing: String): String?

    companion object {
        fun loadOrNull(): RimeBridge? = try {
            System.loadLibrary("rime_jni")
            RimeJniBridge()
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}

/**
 * Copies the Rime shared data (default.yaml, schema, dictionary) out of the
 * APK assets into app-private storage on first run, then reuses it.
 *
 * librime needs a writable user dir for its deployed .bin artifacts; assets
 * are read-only, so a version-stamped copy is the simplest correct approach.
 */
object RimeData {
    private const val TAG = "RimeData"
    private const val VERSION = "2"
    private const val ASSET_ROOT = "rime"

    /** @return [sharedDir, userDir] or null when assets are missing. */
    fun deploy(context: Context): Array<File>? {
        val root = File(context.filesDir, "rime")
        val shared = File(root, "shared")
        val user = File(root, "user")
        val stamp = File(root, "shared.version")
        val upToDate = stamp.isFile && stamp.readText().trim() == VERSION && shared.isDirectory
        if (!upToDate) {
            shared.deleteRecursively()
            user.deleteRecursively()
            user.mkdirs()
            val copied = copyAssets(context, ASSET_ROOT, shared)
            if (!copied) {
                Log.e(TAG, "assets/$ASSET_ROOT is missing; Rime cannot be deployed")
                return null
            }
            stamp.parentFile?.mkdirs()
            stamp.writeText(VERSION)
            Log.i(TAG, "deployed rime data to ${shared.absolutePath}")
        }
        return arrayOf(shared, user)
    }

    private fun copyAssets(context: Context, assetPath: String, target: File): Boolean {
        val children = try {
            context.assets.list(assetPath)
        } catch (e: Exception) {
            Log.e(TAG, "list($assetPath) failed: ${e.message}")
            null
        } ?: return false

        if (children.isEmpty()) {
            // An empty listing means either a leaf asset or a missing path;
            // open() distinguishes the two.
            return try {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "open($assetPath) failed: ${e.message}")
                false
            }
        }

        var ok = target.mkdirs() || target.isDirectory
        for (child in children) {
            val childTarget = File(target, child)
            if (!copyAssets(context, "$assetPath/$child", childTarget)) ok = false
        }
        return ok
    }
}

class LimitedChineseInputEngine : ChineseInputEngine {
    override fun pinyinCandidates(composing: String): List<String> =
        PinyinDictionary.candidates(composing)

    override fun t9Candidates(digits: String): List<String> =
        PinyinDictionary.t9Candidates(digits)

    override fun commitPinyin(composing: String): String? =
        PinyinDictionary.convertFully(composing)
}

/**
 * Rime-backed engine used by the app. If the APK does not contain the
 * librime JNI ABI, or the engine has not deployed yet, it falls back to the
 * local proof-of-concept so the IME stays usable instead of crashing.
 */
class RimeChineseInputEngine private constructor(
    private val bridge: RimeBridge?,
    private val fallback: ChineseInputEngine,
) : ChineseInputEngine {
    constructor() : this(RimeJniBridge.loadOrNull(), LimitedChineseInputEngine())

    @Volatile
    private var prepareStarted = false

    val isNativeRimeAvailable: Boolean
        get() = bridge != null

    val isNativeRimeReady: Boolean
        get() = bridge?.safe { isReady() } ?: false

    val nativeRimeVersion: String?
        get() = bridge?.safe { rimeVersion() }

    override fun prepare(context: Context) {
        val b = bridge ?: return
        if (prepareStarted) return
        prepareStarted = true
        // Deployment compiles the dictionary and can take seconds on first
        // run, so keep it off the main thread; queries fall back until ready.
        Thread({
            try {
                val dirs = RimeData.deploy(context.applicationContext) ?: return@Thread
                val ok = b.safe { initialize(dirs[0].absolutePath, dirs[1].absolutePath) } ?: false
                Log.i(TAG, "librime initialize=$ok version=${b.safe { rimeVersion() }}")
            } catch (t: Throwable) {
                Log.e(TAG, "librime prepare failed", t)
            }
        }, "rime-deploy").start()
    }

    override fun pinyinCandidates(composing: String): List<String> =
        whenReady({ it.pinyinCandidates(composing) }) { fallback.pinyinCandidates(composing) }

    override fun t9Candidates(digits: String): List<String> =
        whenReady({ it.t9Candidates(digits) }) { fallback.t9Candidates(digits) }

    override fun commitPinyin(composing: String): String? =
        whenReady({ it.commitPinyin(composing) }) { fallback.commitPinyin(composing) }

    private inline fun <T> whenReady(native: (RimeBridge) -> T, fallbackBlock: () -> T): T {
        val b = bridge ?: return fallbackBlock()
        if (!(b.safe { isReady() } ?: false)) return fallbackBlock()
        return b.safe { native(b) } ?: fallbackBlock()
    }

    private inline fun <T> RimeBridge.safe(block: RimeBridge.() -> T): T? = try {
        block()
    } catch (_: RuntimeException) {
        null
    } catch (_: UnsatisfiedLinkError) {
        null
    } catch (_: ExceptionInInitializerError) {
        null
    }

    private companion object {
        const val TAG = "RimeEngine"
    }
}

object ChineseInputEngines {
    /** The production direction is Rime; the fallback is only for dev APKs. */
    fun createDefault(): ChineseInputEngine = RimeChineseInputEngine()
}
