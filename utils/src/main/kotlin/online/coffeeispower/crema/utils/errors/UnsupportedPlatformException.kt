package online.coffeeispower.crema.utils.errors

class UnsupportedPlatformException(
    val platform: String = System.getProperty("os.name"),
    val feature: String? = null,
    val reason: String? = null,
    cause: Throwable? = null,
) : UnsupportedOperationException(buildMessage(platform, feature, reason), cause)

private fun buildMessage(platform: String, feature: String?, reason: String?): String = buildString {
    append("Platform '$platform' is not supported")
    if (feature != null) append(" for $feature")
    if (reason != null) append(" because $reason")
}
