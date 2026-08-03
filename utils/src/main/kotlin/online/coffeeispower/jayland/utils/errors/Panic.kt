package online.coffeeispower.jayland.utils.errors

import java.io.PrintStream
import java.util.Arrays
import java.util.function.Supplier

class Panic : Throwable {
    var threadName: String? = Thread.currentThread().name

    constructor(message: String?) : super(message)
    constructor(runtimeException: Throwable) : super(
        runtimeException.message,
        runtimeException.cause,
        true,
        true
    ) {
        setStackTrace(runtimeException.stackTrace)
    }

    constructor() : super()


    override fun printStackTrace(s: PrintStream?) {

        val topOfStack = Arrays.stream(stackTrace)
            .filter { e: StackTraceElement? -> e!!.className.startsWith("online.coffeeispower.jayland") }.findFirst().orElseThrow<Panic?>(
                Supplier { Panic("Expected stack trace to have a stack trace element inside jayland") })
        s!!;
        s.printf(
            "\rthread '%s' panicked at %s:%d:%n",
            threadName,
            topOfStack.fileName,
            topOfStack.lineNumber
        )
        s.println(message)
        s.println("stack backtrace:")
        printFullTraceWithoutHeaders(this, s)
    }

    companion object {
        private fun printFullTraceWithoutHeaders(e: Throwable, s: PrintStream) {
            var i = 0
            for (element in e.stackTrace) {
                s.printf("  %d: %s%n", i++, element)
            }
            val cause = e.cause
            if (cause != null) {
                s.println("Caused by: ")
                printFullTraceWithoutHeaders(cause, s)
            }
        }
    }
}