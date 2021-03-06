package net.ltgt.gradle.errorprone

import com.google.common.truth.Truth.assertThat
import com.google.errorprone.ErrorProneOptions.Severity
import com.google.errorprone.InvalidCommandLineOptionException
import org.gradle.api.InvalidUserDataException
import org.gradle.process.CommandLineArgumentProvider
import org.junit.Assert.fail
import org.junit.Test

class ErrorProneOptionsTest {
    @Test
    fun `generates correct error prone options`() {
        doTestOptions { disableAllChecks = true }
        doTestOptions { allErrorsAsWarnings = true }
        doTestOptions { allDisabledChecksAsWarnings = true }
        doTestOptions { disableWarningsInGeneratedCode = true }
        doTestOptions { ignoreUnknownCheckNames = true }
        doTestOptions { isCompilingTestOnlyCode = true }
        doTestOptions { excludedPaths = ".*/build/generated/.*" }
        doTestOptions { check("ArrayEquals") }
        doTestOptions { check("ArrayEquals" to CheckSeverity.OFF) }
        doTestOptions { check("ArrayEquals", CheckSeverity.WARN) }
        doTestOptions { checks["ArrayEquals"] = CheckSeverity.ERROR }
        doTestOptions { checks = mutableMapOf("ArrayEquals" to CheckSeverity.DEFAULT) }
        doTestOptions { option("Foo") }
        doTestOptions { option("Foo", "Bar") }
        doTestOptions { checkOptions["Foo"] = "Bar" }
        doTestOptions { checkOptions = mutableMapOf("Foo" to "Bar") }

        doTestOptions {
            disableAllChecks = true
            allErrorsAsWarnings = true
            allDisabledChecksAsWarnings = true
            disableWarningsInGeneratedCode = true
            ignoreUnknownCheckNames = true
            isCompilingTestOnlyCode = true
            excludedPaths = ".*/build/generated/.*"
            check("BetaApi")
            check("NullAway", CheckSeverity.ERROR)
            option("Foo")
            option("NullAway:AnnotatedPackages", "net.ltgt.gradle.errorprone")
        }
    }

    private fun doTestOptions(configure: ErrorProneOptions.() -> Unit) {
        val options = ErrorProneOptions().apply(configure)
        val parsedOptions = parseOptions(options)
        assertOptionsEqual(options, parsedOptions)
    }

    @Test
    fun `correctly passes free arguments`() {
        // We cannot test arguments that are not yet covered, and couldn't check patching options
        // (due to class visibility issue), so we're testing equivalence between free-form args
        // vs. args generated by flags (that we already test above on their own)
        doTestOptions({ errorproneArgs.add("-XepDisableAllChecks") }, { disableAllChecks = true })

        doTestOptions(
            { errorproneArgs = mutableListOf("-XepDisableAllChecks", "-Xep:BetaApi") },
            { disableAllChecks = true; check("BetaApi") })

        doTestOptions({
            errorproneArgumentProviders.add(CommandLineArgumentProvider {
                listOf(
                    "-Xep:NullAway:ERROR",
                    "-XepOpt:NullAway:AnnotatedPackages=net.ltgt.gradle.errorprone"
                )
            })
        }, {
            check("NullAway", CheckSeverity.ERROR)
            option("NullAway:AnnotatedPackages", "net.ltgt.gradle.errorprone")
        })
    }

    private fun doTestOptions(configure: ErrorProneOptions.() -> Unit, reference: ErrorProneOptions.() -> Unit) {
        val options = ErrorProneOptions().apply(reference)
        val parsedOptions = parseOptions(ErrorProneOptions().apply(configure))
        assertOptionsEqual(options, parsedOptions)
    }

    @Test
    fun `rejects spaces`() {
        doTestSpaces("-XepExcludedPaths:") {
            excludedPaths = "/home/user/My Projects/project-name/build/generated sources/.*"
        }
        doTestSpaces("-Xep:") { check("Foo Bar") }
        doTestSpaces("-XepOpt:") { option("Foo Bar") }
        doTestSpaces("-XepOpt:") { option("Foo", "Bar Baz") }
        doTestSpaces("-Xep:Foo -Xep:Bar") { errorproneArgs.add("-Xep:Foo -Xep:Bar") }
        doTestSpaces("-Xep:Foo -Xep:Bar") {
            errorproneArgumentProviders.add(CommandLineArgumentProvider { listOf("-Xep:Foo -Xep:Bar") })
        }
    }

    private fun doTestSpaces(argPrefix: String, configure: ErrorProneOptions.() -> Unit) {
        try {
            ErrorProneOptions().apply(configure).toString()
            fail("Should have thrown")
        } catch (e: InvalidUserDataException) {
            assertThat(e).hasMessageThat().startsWith("""Error Prone options cannot contain white space: "$argPrefix""")
        }
    }

    @Test
    fun `rejects colon in check name`() {
        try {
            ErrorProneOptions().apply({ check("ArrayEquals:OFF") }).toString()
            fail("Should have thrown")
        } catch (e: InvalidUserDataException) {
            assertThat(e).hasMessageThat()
                .isEqualTo("""Error Prone check name cannot contain a colon (":"): "ArrayEquals:OFF".""")
        }

        // Won't analyze free-form arguments, but those should be caught (later) by argument parsing
        // This test asserts that we're not being too restrictive, and only try to fail early.
        try {
            parseOptions(ErrorProneOptions().apply {
                ignoreUnknownCheckNames = true
                errorproneArgs.add("-Xep:Foo:Bar")
            })
            fail("Should have thrown")
        } catch (ignore: InvalidCommandLineOptionException) {}
    }

    private fun parseOptions(options: ErrorProneOptions) =
        com.google.errorprone.ErrorProneOptions.processArgs(splitArgs(options.toString()))

    // This is how JavaC "parses" the -Xplugin: values: https://git.io/vx8yI
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun splitArgs(args: String): Array<String> = (args as java.lang.String).split("""\s+""")

    private fun assertOptionsEqual(
        options: ErrorProneOptions,
        parsedOptions: com.google.errorprone.ErrorProneOptions
    ) {
        assertThat(parsedOptions.isDisableAllChecks).isEqualTo(options.disableAllChecks)
        assertThat(parsedOptions.isDropErrorsToWarnings).isEqualTo(options.allErrorsAsWarnings)
        assertThat(parsedOptions.isEnableAllChecksAsWarnings).isEqualTo(options.allDisabledChecksAsWarnings)
        assertThat(parsedOptions.disableWarningsInGeneratedCode()).isEqualTo(options.disableWarningsInGeneratedCode)
        assertThat(parsedOptions.ignoreUnknownChecks()).isEqualTo(options.ignoreUnknownCheckNames)
        assertThat(parsedOptions.isTestOnlyTarget).isEqualTo(options.isCompilingTestOnlyCode)
        assertThat(parsedOptions.excludedPattern?.pattern()).isEqualTo(options.excludedPaths)
        assertThat(parsedOptions.severityMap).containsExactlyEntriesIn(options.checks.mapValues { it.value.toSeverity() })
        assertThat(parsedOptions.flags.flagsMap).containsExactlyEntriesIn(options.checkOptions)
        assertThat(parsedOptions.remainingArgs).isEmpty()
    }

    private fun CheckSeverity.toSeverity(): Severity =
        when (this) {
            CheckSeverity.DEFAULT -> Severity.DEFAULT
            CheckSeverity.OFF -> Severity.OFF
            CheckSeverity.WARN -> Severity.WARN
            CheckSeverity.ERROR -> Severity.ERROR
            else -> throw AssertionError()
        }
}
