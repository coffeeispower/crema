package online.coffeeispower.crema.utils.fds

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Measures the end-to-end wakeup latency: from the moment the fd becomes
 * readable (the `write` syscall) until the suspended coroutine resumes after
 * [FdPoller.awaitReadable].
 */
class PollDispatcherLatencyTest {

    @Test
    fun measure_write_to_resume_latency() = runBlocking {
        val (readEnd, writeEnd) = TestLibc.pipe()
        val dispatcher = PollDispatcher()
        val poller = dispatcher.watch(readEnd)
        try {
            // Warm up JIT, FFM handle adaptation and channel machinery.
            repeat(2_000) {
                TestLibc.write(writeEnd, 1)
                poller.awaitReadable()
                TestLibc.read(readEnd)
            }

            val samples = DoubleArray(20_000)
            for (i in samples.indices) {
                val t0 = System.nanoTime()
                TestLibc.write(writeEnd, 1)
                poller.awaitReadable()
                samples[i] = (System.nanoTime() - t0) / 1_000.0 // µs
                TestLibc.read(readEnd)
            }

            samples.sort()
            val p50 = samples[samples.size / 2]
            val p99 = samples[(samples.size * 0.99).toInt().coerceAtMost(samples.lastIndex)]
            println(
                "fd wakeup latency (write syscall -> coroutine resume) in µs: " +
                    "min=${"%.1f".format(samples.first())} " +
                    "p50=${"%.1f".format(p50)} " +
                    "p99=${"%.1f".format(p99)} " +
                    "avg=${"%.1f".format(samples.average())}",
            )
        } finally {
            poller.close()
            dispatcher.close()
            TestLibc.close(readEnd)
            TestLibc.close(writeEnd)
        }
    }
}
