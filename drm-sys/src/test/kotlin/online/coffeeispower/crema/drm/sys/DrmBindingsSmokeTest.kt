package online.coffeeispower.crema.drm.sys

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DrmBindingsSmokeTest {

    @Test
    fun nativeLibraryLoads() {
        Xf86Drm::class.java
        Xf86Drm_1::class.java
    }

    @Test
    fun connectorTypeConstantsAreBound() {
        assertEquals(11, Xf86Drm_1.DRM_MODE_CONNECTOR_HDMIA())
        assertEquals(10, Xf86Drm_1.DRM_MODE_CONNECTOR_DisplayPort())
        assertEquals(0, Xf86Drm_1.DRM_MODE_CONNECTOR_Unknown())
        assertEquals(1, Xf86Drm_1.DRM_MODE_CONNECTOR_VGA())
    }

    @Test
    fun modeInfoStructLayoutMatchesUapi() {
        val layout = drm_mode_modeinfo.layout()
        assertEquals(0, layout.byteOffset(groupElement("clock")))
        assertEquals(4, layout.byteOffset(groupElement("hdisplay")))
        assertEquals(14, layout.byteOffset(groupElement("vdisplay")))
        assertEquals(68, layout.byteSize())
    }

    @Test
    fun atomicCommitFunctionIsBound() {
        val descriptor = descriptorOf("drmModeAtomicCommit\$descriptor")
        assertEquals(4, descriptor.argumentLayouts().size)
    }

    @Test
    fun kmsFunctionsAreBound() {
        for (name in listOf(
            "drmModeGetResources",
            "drmModeGetConnector",
            "drmModeGetConnectorTypeName",
            "drmModeAtomicCommit",
        )) {
            val descriptor = descriptorOf("$name\$descriptor")
            assertNotNull(descriptor, "expected generated binding for $name")
        }
    }

    private fun descriptorOf(methodName: String): FunctionDescriptor =
        Xf86Drm_1::class.java.getMethod(methodName).invoke(null) as FunctionDescriptor
}
