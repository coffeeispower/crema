package online.coffeeispower.crema.core.synchronization

/**
 * A Latch is a synchronization tool that works like a gate, letting coroutines wait until the latch is opened before they continue. The latch can be either open or closed:
 *
 * - When closed, coroutines that reach the latch suspend until it opens.
 * - When open, coroutines pass through immediately.
 *
 * Once opened, a latch typically stays open but can also be closed again if needed.
 */
interface Latch {
    suspend fun await()
    suspend fun whenOpen(block: suspend () -> Unit) = await().also { block() }
}