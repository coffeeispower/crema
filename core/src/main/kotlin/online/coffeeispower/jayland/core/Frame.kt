package online.coffeeispower.jayland.core

data class Frame(val buffer: GPUScanoutBuffer, val submission: Submission)

data class FrameResult(
    val presented: Boolean,
    val presentedAt: Long,
    val frameSeq: Long,
)
