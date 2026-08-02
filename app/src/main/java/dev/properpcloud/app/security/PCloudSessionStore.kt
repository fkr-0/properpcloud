package dev.properpcloud.app.security

import dev.properpcloud.source.pcloud.PCloudSession

interface PCloudSessionStore {
    fun read(): PCloudSession?
    fun write(session: PCloudSession)
    fun clear()
}
