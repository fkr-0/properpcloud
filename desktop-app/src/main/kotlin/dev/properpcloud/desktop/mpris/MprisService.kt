package dev.properpcloud.desktop.mpris

import dev.properpcloud.core.model.AudioTrack
import dev.properpcloud.desktop.playback.MpvState
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicReference

interface MprisActions {
    fun playPause()
    fun play()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun seek(offsetMillis: Long)
    fun seekAbsolute(positionMillis: Long)
    fun raise()
    fun quit()
}

data class MprisSnapshot(
    val track: AudioTrack? = null,
    val playback: MpvState = MpvState(),
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
)

class MprisService(private val actions: MprisActions) : MediaPlayer2, Player, Properties, AutoCloseable {
    private val snapshot = AtomicReference(MprisSnapshot())
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build().also {
        it.requestBusName(BUS_NAME)
        it.exportObject(OBJECT_PATH, this)
    }

    fun update(value: MprisSnapshot) {
        snapshot.set(value)
        runCatching {
            connection.sendMessage(
                Properties.PropertiesChanged(
                    OBJECT_PATH,
                    PLAYER_INTERFACE,
                    playerProperties(),
                    emptyList(),
                ),
            )
        }
    }

    override fun Raise() = actions.raise()
    override fun Quit() = actions.quit()
    override fun Next() = actions.next()
    override fun Previous() = actions.previous()
    override fun Pause() = actions.pause()
    override fun PlayPause() = actions.playPause()
    override fun Stop() = actions.stop()
    override fun Play() = actions.play()
    override fun Seek(offsetMicroseconds: Long) = actions.seek(offsetMicroseconds / 1_000)
    override fun SetPosition(trackId: DBusPath, positionMicroseconds: Long) = actions.seekAbsolute(positionMicroseconds / 1_000)
    override fun OpenUri(uri: String) = Unit
    override fun isRemote(): Boolean = false
    override fun getObjectPath(): String = OBJECT_PATH

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        (GetAll(interfaceName)[propertyName]
            ?: error("unknown MPRIS property $interfaceName.$propertyName")) as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        when (interfaceName to propertyName) {
            PLAYER_INTERFACE to "Rate" -> Unit
            PLAYER_INTERFACE to "Volume" -> Unit
            PLAYER_INTERFACE to "LoopStatus" -> Unit
            PLAYER_INTERFACE to "Shuffle" -> Unit
            ROOT_INTERFACE to "Fullscreen" -> Unit
            else -> error("MPRIS property is read-only: $interfaceName.$propertyName")
        }
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
        ROOT_INTERFACE -> rootProperties()
        PLAYER_INTERFACE -> playerProperties()
        else -> emptyMap()
    }

    private fun rootProperties(): Map<String, Variant<*>> = linkedMapOf(
        "CanQuit" to Variant(true),
        "Fullscreen" to Variant(false),
        "CanSetFullscreen" to Variant(false),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("properpcloud"),
        "DesktopEntry" to Variant("properpcloud"),
        "SupportedUriSchemes" to Variant(listOf("file", "https"), "as"),
        "SupportedMimeTypes" to Variant(listOf("audio/mpeg", "audio/flac", "audio/ogg", "audio/wav", "audio/mp4"), "as"),
    )

    private fun playerProperties(): Map<String, Variant<*>> {
        val current = snapshot.get()
        val track = current.track
        val metadata = linkedMapOf<String, Variant<*>>(
            "mpris:trackid" to Variant(DBusPath(track?.let(::trackPath) ?: "/org/mpris/MediaPlayer2/Track/none")),
            "xesam:title" to Variant(track?.taggedTitle ?: track?.filenameStem ?: "Nothing playing"),
        )
        track?.durationMillis?.let { metadata["mpris:length"] = Variant(it * 1_000) }
        track?.name?.let { metadata["xesam:url"] = Variant("properpcloud:${track.id.value}") }
        return linkedMapOf(
            "PlaybackStatus" to Variant(if (current.playback.idle) "Stopped" else if (current.playback.paused) "Paused" else "Playing"),
            "LoopStatus" to Variant("None"),
            "Rate" to Variant(1.0),
            "Shuffle" to Variant(false),
            "Metadata" to Variant(metadata, "a{sv}"),
            "Volume" to Variant(1.0),
            "Position" to Variant(current.playback.positionMillis * 1_000),
            "MinimumRate" to Variant(0.5),
            "MaximumRate" to Variant(4.0),
            "CanGoNext" to Variant(current.canNext),
            "CanGoPrevious" to Variant(current.canPrevious),
            "CanPlay" to Variant(track != null),
            "CanPause" to Variant(track != null),
            "CanSeek" to Variant(track != null),
            "CanControl" to Variant(true),
        )
    }

    override fun close() {
        runCatching { connection.unExportObject(OBJECT_PATH) }
        runCatching { connection.releaseBusName(BUS_NAME) }
        connection.disconnect()
    }

    companion object {
        const val BUS_NAME = "org.mpris.MediaPlayer2.properpcloud"
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
        fun trackPath(track: AudioTrack): String = "/org/mpris/MediaPlayer2/Track/" + track.id.value.replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}
