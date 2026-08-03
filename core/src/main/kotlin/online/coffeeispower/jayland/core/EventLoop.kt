package online.coffeeispower.jayland.core

import kotlinx.coroutines.CoroutineScope

sealed interface EventLoopEvent {
    data class StartMonitors(val connectors: List<Connector>) : EventLoopEvent
    data class MonitorConnected(val connector: Connector) : EventLoopEvent
    data class MonitorDisconnected(val connector: Connector) : EventLoopEvent
}

interface EventLoop : AutoCloseable {
    fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit)
}
