package online.coffeeispower.jayland.core.platform.linux

import online.coffeeispower.jayland.core.DrmProps
import online.coffeeispower.jayland.core.GPU

interface DrmGPU: GPU {
    val drmProps: DrmProps;
}