package online.coffeeispower.jayland.core.graphics.presentation

data class FrameResult(
    val presented: Boolean,
    val presentedAt: Long,
    val frameSeq: Long,
)