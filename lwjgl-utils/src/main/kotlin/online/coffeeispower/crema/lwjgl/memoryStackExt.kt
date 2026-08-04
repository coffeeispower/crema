package online.coffeeispower.crema.lwjgl

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@OptIn(ExperimentalContracts::class)
inline fun MemoryStack.outPointer(function: (PointerBuffer) -> Unit): Long {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocPointer(1);
    function(buffer);
    return buffer.get(0);
}

@OptIn(ExperimentalContracts::class)
inline fun MemoryStack.outInt(function: (IntBuffer) -> Unit): Int {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocInt(1);
    function(buffer);
    return buffer.get(0)
}

@OptIn(ExperimentalContracts::class)
inline fun MemoryStack.outLong(function: (LongBuffer) -> Unit): Long {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocLong(1);
    function(buffer);
    return buffer.get(0);
}

@OptIn(ExperimentalContracts::class)
inline fun <T> MemoryStack.outPointerPair(function: (PointerBuffer) -> T): Pair<Long, T> {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocPointer(1);
    val result = function(buffer);
    return Pair(buffer.get(0), result);
}

@OptIn(ExperimentalContracts::class)
inline fun <T> MemoryStack.outIntPair(function: (IntBuffer) -> T): Pair<Int, T> {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocInt(1);
    val result = function(buffer);
    return Pair(buffer.get(0), result);
}


@OptIn(ExperimentalContracts::class)
inline fun <T> MemoryStack.outLongPair(function: (LongBuffer) -> T): Pair<Long, T> {
    contract {
        callsInPlace(function, InvocationKind.EXACTLY_ONCE)
    }
    val buffer = this.callocLong(1);
    val result = function(buffer);
    return Pair(buffer.get(0), result);
}

@OptIn(ExperimentalContracts::class)
inline fun <T> memStack(block: MemoryStack.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    MemoryStack.stackPush().use { stack ->
        return stack.block()
    }
}