package online.coffeeispower.jayland.core.graphics.presentation

import online.coffeeispower.jayland.core.platform.linux.GPUScanoutBuffer
import online.coffeeispower.jayland.core.graphics.gpu.Submission

data class Frame(val buffer: GPUScanoutBuffer, val submission: Submission)

