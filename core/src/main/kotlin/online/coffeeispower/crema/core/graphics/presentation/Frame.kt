package online.coffeeispower.crema.core.graphics.presentation

import online.coffeeispower.crema.core.platform.linux.GPUScanoutImageBuffer
import online.coffeeispower.crema.core.graphics.gpu.Submission

data class Frame(val buffer: GPUScanoutImageBuffer, val submission: Submission)

