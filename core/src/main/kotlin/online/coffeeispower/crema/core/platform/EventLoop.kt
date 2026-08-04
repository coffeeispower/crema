package online.coffeeispower.crema.core.platform

import kotlinx.coroutines.CoroutineScope
import online.coffeeispower.crema.core.monitors.Connector

sealed interface EventLoopEvent {
    data class StartMonitors(val connectors: List<Connector>) : EventLoopEvent
    data class MonitorConnected(val connector: Connector) : EventLoopEvent
    data class MonitorDisconnected(val connector: Connector) : EventLoopEvent
}

interface EventLoop : AutoCloseable {
    fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit)
}
