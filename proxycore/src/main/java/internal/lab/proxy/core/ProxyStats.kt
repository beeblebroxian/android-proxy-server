package internal.lab.proxy.core

/** Live aggregate state for both proxy listeners. */
public data class ProxyStats(
    public val running: Boolean,
    public val activeConnections: Int,
    public val totalConnections: Long,
    public val sentBytes: Long,
    public val receivedBytes: Long,
    public val lastError: String?,
)
