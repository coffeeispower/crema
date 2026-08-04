package online.coffeeispower.crema.core.graphics.presentation

import online.coffeeispower.crema.core.platform.linux.GPUScanoutBuffer
import online.coffeeispower.crema.core.graphics.gpu.Submission

data class Frame(val buffer: GPUScanoutBuffer, val submission: Submission)

