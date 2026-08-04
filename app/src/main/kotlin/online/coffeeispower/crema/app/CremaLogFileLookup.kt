package online.coffeeispower.crema.app

import online.coffeeispower.crema.utils.errors.CrashReport
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.lookup.StrLookup

/**
 * Resolves `${cremaLogFile:path}` in the log4j2 configuration to
 * [CrashReport.logFile], keeping the log path a single source of truth: the
 * only default lives in Kotlin, and the config just asks for it.
 *
 * Registered through the Log4j2 annotation processor (kapt), which generates
 * `Log4j2Plugins.dat` at compile time — no package scanning needed.
 */
@Plugin(name = "cremaLogFile", category = StrLookup.CATEGORY)
class CremaLogFileLookup : StrLookup {
    override fun lookup(key: String?): String = CrashReport.logFile.toString()

    override fun lookup(event: LogEvent?, key: String?): String = CrashReport.logFile.toString()
}
