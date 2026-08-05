package dev.properpcloud.desktop.platform

import dev.properpcloud.desktop.playback.MpvState
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal

@DBusInterfaceName("org.freedesktop.login1.Manager")
private interface Login1Manager : DBusInterface {
    class PrepareForSleep(path: String, val sleeping: Boolean) : DBusSignal(path, sleeping)
}

class LogindSleepMonitor(
    onPrepareForSleep: (Boolean) -> Unit,
) : AutoCloseable {
    private val connection: DBusConnection = DBusConnectionBuilder.forSystemBus().build()
    private val login1Owner: String = connection.getDBusOwnerName("org.freedesktop.login1")
    private val handler = DBusSigHandler<Login1Manager.PrepareForSleep> { signal ->
        if (signal.path == "/org/freedesktop/login1") onPrepareForSleep(signal.sleeping)
    }
    private val subscription: AutoCloseable = connection.addSigHandler(
        Login1Manager.PrepareForSleep::class.java,
        login1Owner,
        handler,
    )

    override fun close() {
        runCatching { subscription.close() }
        runCatching { connection.close() }
    }
}

data class SleepTransitionDecision(
    val forceCheckpoint: Boolean = false,
    val pausePlayback: Boolean = false,
    val refreshAndResume: Boolean = false,
    val status: String,
)

class SleepTransitionPolicy {
    private var resumeOnWake = false

    @Synchronized
    fun transition(preparingForSleep: Boolean, playback: MpvState): SleepTransitionDecision {
        if (preparingForSleep) {
            resumeOnWake = playback.running && !playback.paused && !playback.idle && !playback.unexpectedExit
            return SleepTransitionDecision(
                forceCheckpoint = true,
                pausePlayback = resumeOnWake,
                status = if (resumeOnWake) {
                    "Checkpointed and paused before system sleep"
                } else {
                    "Checkpointed before system sleep"
                },
            )
        }

        val resume = resumeOnWake
        resumeOnWake = false
        return SleepTransitionDecision(
            refreshAndResume = resume,
            status = if (resume) {
                "Refreshing the stream after system resume"
            } else {
                "System resume observed"
            },
        )
    }
}
