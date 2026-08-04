package online.coffeeispower.crema.core.graphics.presentation

data class FrameResult(
    val presented: Boolean,
    val presentedAt: Long,
    val frameSeq: Long,
)