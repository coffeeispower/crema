package online.coffeeispower.crema.core.monitors

interface ConnectorManager : AutoCloseable {
    val connectors: List<Connector>
}