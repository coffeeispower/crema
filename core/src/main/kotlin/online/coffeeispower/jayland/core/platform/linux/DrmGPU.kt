package online.coffeeispower.jayland.core.platform.linux

import online.coffeeispower.jayland.core.graphics.gpu.GPU

interface DrmGPU: GPU {
    val drmProps: DrmProps;
}

data class DrmProps(
    val render: Pair<Long, Long>?,
    val primary: Pair<Long, Long>?,
)