package io.washy.sm8750.tuner

internal class CompatibilityReport(
    val text: String,
    rootReport: String?,
) {
    val rootCollected = rootReport != null

    private val rootValues = buildMap {
        rootReport?.lineSequence()?.forEach { line ->
            val separator = line.indexOf('=')
            val key = line.take(separator.coerceAtLeast(0))
            if (key.isNotEmpty() && key.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
                putIfAbsent(key, line.substring(separator + 1).trim())
            }
        }
    }

    val model = text.lineSequence().firstOrNull { it.startsWith("MODEL=") }?.substringAfter('=').orEmpty()
    val kernel = text.lineSequence().firstOrNull { it.startsWith("KERNEL=") }?.substringAfter('=').orEmpty()
    val cdtDisk = rootValues["CDT_DISK"]
    val cdtRead = rootValues["CDT_READ"]
    val recoveryPending = rootValues["CDT_TRANSACTION"] == "pending_recovery"
    val readOnlyFlags = listOf("CDT_BLOCKDEV_RO", "CDT_SYSFS_RO", "CDT_PARENT_SYSFS_RO").mapNotNull(rootValues::get)
    val scmiBus = rootValues["SCMI_BUS"]
    val selinuxMode = rootValues["SELINUX_MODE"]
}
