package github.xitadoptus.macro.update

import java.util.Locale

object UpdateVersionPolicy {
    fun shouldNotify(currentVersion: String, latestTag: String?): Boolean {
        val current = normalize(currentVersion)
        val latest = normalize(latestTag)
        if (current.isBlank() || latest.isBlank()) return false
        return latest != current && !latest.endsWith("-$current") && !latest.endsWith("_$current")
    }

    fun normalize(value: String?): String {
        return value.orEmpty().trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)
    }
}
