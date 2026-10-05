import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * `:app-lite:verifyNoCompose` (spec 14 §1.3 rule 3, D8): fails if any `androidx.compose`
 * artifact resolves in the lite app's runtime classpath.
 */
abstract class VerifyNoComposeTask : DefaultTask() {
    @get:Input abstract val classpathName: Property<String>

    /** group:name:version of every module in the resolved graph. */
    @get:Input abstract val resolvedModules: ListProperty<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val offenders = resolvedModules.get().filter { it.startsWith("androidx.compose") }
        report.get().asFile.writeText(
            if (offenders.isEmpty()) "OK: no androidx.compose in ${classpathName.get()}\n"
            else offenders.joinToString("\n", postfix = "\n"),
        )
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "verifyNoCompose: the lite app must not have Compose on its classpath (D8). " +
                    "Found in ${classpathName.get()}:\n  " + offenders.joinToString("\n  "),
            )
        }
    }

    companion object {
        fun collect(root: ResolvedComponentResult): List<String> {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(listOf(root))
            val visited = HashSet<ResolvedComponentResult>()
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (!visited.add(c)) continue
                (c.id as? ModuleComponentIdentifier)?.let { seen += "${it.group}:${it.module}:${it.version}" }
                c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                    .forEach { queue.add(it.selected) }
            }
            return seen.sorted()
        }
    }
}

/**
 * `:app-lite:verifyPatchedVosk<Variant>` (spec 14 §1.9, inv04 §8 risk 2): unzips the packaged
 * APK and fails unless stderr/stdin/stdout are STB_WEAK in every lib/<abi>/libvosk.so.
 * The logic lives in tools/native/verify_vosk_patch.py so it can also run by hand / in CI.
 */
abstract class VerifyPatchedVoskTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty()
        if (apks.isEmpty()) throw GradleException("verifyPatchedVosk: no APK in ${apkDirectory.get().asFile}")
        val out = java.io.ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(listOf("python3", script.get().asFile.absolutePath) + apks.map { it.absolutePath })
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = true
        }
        report.get().asFile.writeText(out.toString())
        logger.lifecycle(out.toString().trim())
        if (result.exitValue != 0) {
            throw GradleException("verifyPatchedVosk failed: an unpatched libvosk.so would crash on API 21-22.\n$out")
        }
    }
}
