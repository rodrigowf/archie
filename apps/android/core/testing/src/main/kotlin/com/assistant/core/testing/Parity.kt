package com.assistant.core.testing

import java.io.File
import java.util.ServiceLoader

/**
 * Parity-harness plumbing (spec 14 §6.1/§6.2, A-04).
 *
 * The parity tests are written before the implementations exist, so they reach the implementation
 * through two late-bound contracts:
 *  - `META-INF/services/<port>` entries ([Parity.load]) for the `*Core` entry points;
 *  - fixed `Tuning` object names read reflectively ([TuningProbe]).
 */
object Parity {
    /** Loads the single implementation of [port] registered via `java.util.ServiceLoader`. */
    fun <T : Any> load(port: Class<T>, owner: String): T {
        val it = ServiceLoader.load(port, port.classLoader).iterator()
        if (!it.hasNext()) {
            throw AssertionError(
                "No ${port.name} implementation registered in META-INF/services (owner: $owner). " +
                    "Add src/main/resources/META-INF/services/${port.name} naming the implementation class.",
            )
        }
        return it.next()
    }

    inline fun <reified T : Any> load(owner: String): T = load(T::class.java, owner)
}

/**
 * Reads a `Tuning` object's values by name: `const val` / `@JvmField` (public static field) or a
 * plain `val` (getter on `INSTANCE`). Fails with the owning work package when missing.
 */
class TuningProbe(private val fqcn: String, private val owner: String) {
    private val cls: Class<*> by lazy {
        try {
            Class.forName(fqcn)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("Tuning object $fqcn is missing (owner: $owner)", e)
        }
    }

    fun raw(name: String): Any? {
        runCatching { cls.getField(name) }.getOrNull()?.let { return it.get(null) }
        val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull()
        val getter = runCatching { cls.getMethod("get$name") }.getOrNull()
        if (getter != null) return getter.invoke(instance)
        throw AssertionError("$fqcn.$name is missing (owner: $owner)")
    }

    fun long(name: String): Long = (raw(name) as Number).toLong()
    fun int(name: String): Int = (raw(name) as Number).toInt()
    fun double(name: String): Double = (raw(name) as Number).toDouble()
    fun float(name: String): Float = (raw(name) as Number).toFloat()
    fun string(name: String): String = raw(name) as String

    @Suppress("UNCHECKED_CAST")
    fun list(name: String): List<Any?> = when (val v = raw(name)) {
        is List<*> -> v
        is Collection<*> -> v.toList()
        is LongArray -> v.toList()
        is IntArray -> v.toList()
        is Array<*> -> v.toList()
        else -> throw AssertionError("$fqcn.$name is not a list: $v")
    }
}

/** Locates the `android/` root from the test JVM's working directory (any module). */
object ArchieRoot {
    val dir: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val settings = File(d, "settings.gradle.kts")
            if (settings.isFile && settings.readText().contains("rootProject.name = \"archie-android\"")) return@lazy d
            d = d.parentFile
        }
        throw AssertionError("android root not found above ${System.getProperty("user.dir")}")
    }

    fun file(relative: String): File = File(dir, relative)

    /** All `.kt` files under `core/<module>/src/test`. */
    fun testSources(vararg modules: String): List<File> = modules.flatMap { m ->
        file("core/$m/src/test").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    val voiceModules = arrayOf("audio", "voice", "wakeword", "voice-host")
}

/**
 * Marks a test as the assertion for the given `old_constants.json` row ids (behavioural constants
 * without a scalar Tuning value: grammar shape, write policy, mic source, ...). Scalar rows are
 * referenced through [TuningPins] calls. `ConstantCoverageTest` checks every LB/wire id is referenced.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class PinsConstant(vararg val ids: String)
