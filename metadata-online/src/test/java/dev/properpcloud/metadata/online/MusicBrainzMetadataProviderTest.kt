package dev.properpcloud.metadata.online

import dev.properpcloud.core.model.TagField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class MusicBrainzMetadataProviderTest {
    @Test
    fun buildsConstrainedRecordingQuery() {
        val provider = provider(HttpResponse(200, EMPTY_RESULT))

        val uri = provider.buildSearchUri(
            MetadataSearchQuery(title = "A Door", artist = "The Badgers", durationMillis = 60_000),
            limit = 7,
        )

        assertEquals("musicbrainz.org", uri.host)
        assertTrue(uri.rawQuery.contains("limit=7"))
        assertTrue(uri.rawQuery.contains("recording%3A%22A+Door%22"))
        assertTrue(uri.rawQuery.contains("artist%3A%22The+Badgers%22"))
        assertTrue(uri.rawQuery.contains("dur%3A%5B58000+TO+62000%5D"))
    }

    @Test
    fun parsesCandidatesWithProvenanceAndCoverArt() = runTest {
        var requestUri: URI? = null
        var userAgent: String? = null
        val provider = MusicBrainzMetadataProvider(
            applicationName = "properpcloud",
            applicationVersion = "0.1.2",
            contactUrl = "https://github.com/fkr-0/properpcloud",
            transport = HttpTransport { uri, agent ->
                requestUri = uri
                userAgent = agent
                HttpResponse(200, RECORDING_RESULT)
            },
            rateGate = RequestRateGate(0),
        )

        val candidate = provider.search(MetadataSearchQuery(isrc = "DEMO123"), limit = 1).single()

        assertEquals("recording-uuid", candidate.id)
        assertEquals("A Door in the Rain", candidate.fields[TagField.TITLE]?.value)
        assertEquals("The Badgers", candidate.fields[TagField.ARTIST]?.value)
        assertEquals("2026", candidate.fields[TagField.YEAR]?.value)
        assertEquals(0.93, candidate.score, 0.001)
        assertEquals(
            "https://coverartarchive.org/release/12345678-1234-1234-1234-123456789abc/front-500",
            candidate.coverArtUrl,
        )
        assertEquals("musicbrainz.org", requestUri?.host)
        assertEquals("properpcloud/0.1.2 (https://github.com/fkr-0/properpcloud)", userAgent)
    }

    @Test
    fun rejectsDocumentTypesAndExternalEntities() {
        val provider = provider(HttpResponse(200, EMPTY_RESULT))
        val malicious = """
            <?xml version="1.0"?>
            <!DOCTYPE metadata [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#">
              <recording-list><recording id="bad"><title>&secret;</title></recording></recording-list>
            </metadata>
        """.trimIndent().toByteArray()

        assertThrows(Exception::class.java) { provider.parseRecordingSearch(malicious) }
    }

    @Test
    fun acoustIdRequestRedactsConfiguredApplicationKey() {
        val request = AcoustIdLookupRequest(
            applicationKey = "sensitive-application-key",
            fingerprint = AcousticFingerprint(60, "encoded-fingerprint"),
        )

        assertEquals(
            "AcoustIdLookupRequest(applicationKey=<redacted>, fingerprint=AcousticFingerprint(durationSeconds=60, encodedFingerprint=<redacted>))",
            request.toString(),
        )
    }

    @Test
    fun serializesRequestsThroughRateGate() = runTest {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val gate = RequestRateGate(
            minimumIntervalMillis = 1_100,
            nowMillis = { now },
            sleeper = { wait -> waits += wait; now += wait },
        )

        gate.run { Unit }
        now += 100
        gate.run { Unit }

        assertEquals(listOf(1_000L), waits)
    }

    private fun provider(response: HttpResponse) = MusicBrainzMetadataProvider(
        applicationName = "properpcloud",
        applicationVersion = "0.1.2",
        contactUrl = "https://github.com/fkr-0/properpcloud",
        transport = HttpTransport { _, _ -> response },
        rateGate = RequestRateGate(0),
    )

    private companion object {
        val EMPTY_RESULT = """<?xml version="1.0"?><metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#"><recording-list count="0"/></metadata>""".toByteArray()
        val RECORDING_RESULT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata xmlns="http://musicbrainz.org/ns/mmd-2.0#" xmlns:ext="http://musicbrainz.org/ns/ext#-2.0">
              <recording-list count="1">
                <recording id="recording-uuid" ext:score="93">
                  <title>A Door in the Rain</title>
                  <artist-credit><name-credit><artist id="artist-id"><name>The Badgers</name></artist></name-credit></artist-credit>
                  <release-list count="1">
                    <release id="12345678-1234-1234-1234-123456789abc">
                      <title>Cloud Chapters</title><date>2026-08-02</date>
                    </release>
                  </release-list>
                </recording>
              </recording-list>
            </metadata>
        """.trimIndent().toByteArray()
    }
}
