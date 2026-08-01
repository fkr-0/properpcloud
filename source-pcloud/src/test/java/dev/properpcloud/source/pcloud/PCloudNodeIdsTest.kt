package dev.properpcloud.source.pcloud

import org.junit.Assert.assertEquals
import org.junit.Test

class PCloudNodeIdsTest {
    @Test
    fun `folder ids round trip`() {
        val parsed = PCloudNodeIds.parse(PCloudNodeIds.folder(42))
        assertEquals(PCloudNodeKind.FOLDER, parsed.kind)
        assertEquals(42L, parsed.numericId)
    }

    @Test
    fun `file ids round trip`() {
        val parsed = PCloudNodeIds.parse(PCloudNodeIds.file(99))
        assertEquals(PCloudNodeKind.FILE, parsed.kind)
        assertEquals(99L, parsed.numericId)
    }
}
