package dev.properpcloud.source.pcloud

import com.pcloud.sdk.ApiError
import dev.properpcloud.core.model.StreamResolutionFailureKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class PCloudStreamResolutionFailureTest {
    @Test
    fun `documented file not found is terminal for only that queue item`() {
        val failure = classifyPCloudStreamResolutionFailure(ApiError(2009, "File not found"))

        assertEquals(StreamResolutionFailureKind.ITEM_UNAVAILABLE, failure.kind)
    }

    @Test
    fun `authentication internal and transport failures remain transient`() {
        assertEquals(
            StreamResolutionFailureKind.TRANSIENT,
            classifyPCloudStreamResolutionFailure(ApiError(2000, "Log in failed")).kind,
        )
        assertEquals(
            StreamResolutionFailureKind.TRANSIENT,
            classifyPCloudStreamResolutionFailure(ApiError(5000, "Internal error")).kind,
        )
        assertEquals(
            StreamResolutionFailureKind.TRANSIENT,
            classifyPCloudStreamResolutionFailure(IOException("offline")).kind,
        )
    }
}
