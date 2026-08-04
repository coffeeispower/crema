package online.coffeeispower.jayland.core.monitors

interface ConnectorManager : AutoCloseable {
    val connectors: List<Connector>
}