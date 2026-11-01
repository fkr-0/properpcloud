package dev.fkr.properpcloud.app.security

import dev.fkr.properpcloud.source.pcloud.PCloudSession

interface PCloudSessionStore {
    fun read(): PCloudSession?
    fun write(session: PCloudSession)
    fun clear()
}
