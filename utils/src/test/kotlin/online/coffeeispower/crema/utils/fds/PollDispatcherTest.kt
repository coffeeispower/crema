package online.coffeeispower.crema.utils.fds

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class PollDispatcherTest {

    @Test
    fun awaitReadable_returns_when_fd_becomes_readable() = runBlocking {
        val (readEnd, writeEnd) = TestLibc.pipe()
        try {
            val dispatcher = PollDispatcher()
            val poller = dispatcher.watch(readEnd)
            try {
                launch {
                    delay(100.milliseconds)
                    TestLibc.write(writeEnd, 'x'.code.toByte())
                }
                withTimeout(5_000.milliseconds) {
                    poller.awaitReadable()
                }
            } finally {
                poller.close()
                dispatcher.close()
            }
        } finally {
            TestLibc.close(readEnd)
            TestLibc.close(writeEnd)
        }
    }

    @Test
    fun onReadable_selects_between_two_fds() = runBlocking {
        val (readA, writeA) = TestLibc.pipe()
        val (readB, writeB) = TestLibc.pipe()
        try {
            val dispatcher = PollDispatcher()
            val pollerA = dispatcher.watch(readA)
            val pollerB = dispatcher.watch(readB)
            try {
                launch {
                    delay(100.milliseconds)
                    TestLibc.write(writeB, 'y'.code.toByte())
                }
                val winner = withTimeout(5_000.milliseconds) {
                    select {
                        pollerA.onReadable { pollerA }
                        pollerB.onReadable { pollerB }
                    }
                }
                assertEquals(pollerB, winner)
            } finally {
                pollerA.close()
                pollerB.close()
                dispatcher.close()
            }
        } finally {
            TestLibc.close(readA)
            TestLibc.close(writeA)
            TestLibc.close(readB)
            TestLibc.close(writeB)
        }
    }
}
