# Core media and source API

Package: `dev.properpcloud.core.model`

## Stable identities

```kotlin
@JvmInline value class SourceId(val value: String)
@JvmInline value class NodeId(val value: String)
```

Both values reject blank strings. A node is unique only together with its source. Persist or compare the pair, not the node string alone.

`MediaIdentity.encode` produces a reversible internal composite key using a unit-separator delimiter. Consumers should use `MediaIdentity.decode` instead of parsing the format themselves.

## Media nodes

```kotlin
sealed interface MediaNode {
    val sourceId: SourceId
    val id: NodeId
    val parentId: NodeId?
    val name: String
    val modifiedAtEpochMillis: Long?
}
```

`AudioFolder` permits a null parent for a source root. `AudioTrack` requires a parent folder and may carry optional content type, size, disc, track, tagged title, and duration hints.

`AudioTrack.filenameStem` removes only the final filename extension and remains available when no embedded title exists.

## AudioSource

```kotlin
interface AudioSource {
    val id: SourceId
    val root: AudioFolder

    suspend fun list(folderId: NodeId): List<MediaNode>
    suspend fun load(nodeId: NodeId): MediaNode
    suspend fun resolveStream(trackId: NodeId): StreamHandle
    suspend fun inspect(nodeId: NodeId): NodeInspection
}
```

### Method contracts

- `list` returns direct children only. Recursive traversal belongs to `FolderQueueAssembler`.
- `load` returns a folder or playable audio node and rejects unsupported node kinds.
- `resolveStream` returns a currently usable capability. Callers must tolerate expiration and resolution failure.
- `inspect` is read-only and returns display-safe fields. Secrets and signed URLs are forbidden.

## StreamHandle

```kotlin
data class StreamHandle(
    val url: String,
    val expiresAtEpochMillis: Long? = null,
    val contentType: String? = null,
)
```

The URL may be a short-lived HTTPS capability or a local `file:` URI. It exists only between resolution and playback loading.

## MetadataContentSource

`MetadataContentSource.prepareMetadataSource` downloads or copies a provider file into a caller-selected staging destination and returns `PreparedMetadataSource` with revision, content hash, and size evidence. Implementations must delete partial output on failure.
