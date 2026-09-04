# Server-backed pCloud library

properpcloud can build and serve its library on a Linux server instead of making every client recursively scan pCloud. The server keeps the folder-first model: paths and filenames remain visible data, while pCloud file/folder IDs are used as stable node identity when the provider metadata refresh is available.

## 1. Configure pCloud and the mount

Configure an rclone remote named `pcloud` using rclone's pCloud backend. Keep the rclone configuration permission-restricted; do not put OAuth tokens in `server.env`.

Install `scripts/properpcloud-rclone-mount.sh` as `~/.local/bin/properpcloud-rclone-mount`. By default it mounts the complete remote at `~/.local/share/properpcloud/mount/pcloud` with full VFS caching and read-only source semantics. Set `PROPERPCLOUD_PCLOUD_REMOTE` or `PROPERPCLOUD_MOUNT_ROOT` in `~/.config/properpcloud/server.env` to override those values.

The full remote mount intentionally covers music libraries, sample packs, stems, and project folders. The catalog records all folders and files but performs embedded-audio inspection only for supported audio extensions.

## 2. Configure the server pCloud REST session

Create `~/.config/properpcloud/server/pcloud-session.json` with mode `0600`:

    {
      "accessToken": "<pCloud OAuth access token>",
      "apiHost": "eapi.pcloud.com",
      "userId": 123456789
    }

Use `api.pcloud.com` for a United States account and `eapi.pcloud.com` for a European account. The server rejects other hosts and refuses group/world-readable secret files. The session file is reloaded when it changes and once on an authentication rejection, so token replacement does not require a server restart.

pCloud access tokens currently do not have a refresh-token/expiry lifecycle. Therefore properpcloud does not invent a refresh-token flow; rotation means replacing the protected session file. The REST client reuses one JDK HTTP/2 client so connections and TLS sessions can be pooled.

## 3. API authentication and network exposure

The default server bind is `127.0.0.1:8787`, which is intentionally local-only. For a non-loopback bind, set `PROPERPCLOUD_SERVER_API_TOKEN_FILE` to an owner-only file containing a random bearer token. The server refuses a non-loopback bind without it.

For Android or another remote client, put a TLS reverse proxy in front of the loopback server and set `PROPERPCLOUD_PUBLIC_BASE_URL=https://library.example`. Remote clients reject plain HTTP. The API token protects catalog operations; playback receives only a random five-minute stream ticket. Neither the API bearer token nor a pCloud signed URL is persisted as media identity.

## 4. Catalog generation

The server maintains `~/.local/share/properpcloud/library.db` by default. A scan:

1. refreshes the pCloud recursive metadata tree when the REST API is reachable;
2. walks the mounted storage without following symbolic links;
3. preserves pCloud file/folder IDs as stable node IDs where the provider mapping is known;
4. compares size and modification time with the previous generation;
5. hashes and parses only new/changed audio files;
6. extracts embedded tags, duration, best-effort sample rate/channels/bit depth, and path-derived artist/album/genre fallbacks;
7. keeps SHA-256 fingerprints for duplicate detection;
8. removes disappeared entries only after a complete traversal.

If pCloud is offline but the rclone mount is readable, scanning continues using cached provider identity. If the mount is unavailable, the scan fails without deleting the last good catalog, and browse/search remain available from SQLite with degraded status.

The server starts scans outside HTTP request threads and repeats them every 15 minutes by default. Configure `PROPERPCLOUD_SCAN_INTERVAL_MINUTES` or set `PROPERPCLOUD_SCAN_ON_START=0` when another scheduler owns scans.

## 5. CLI and HTTP API

The desktop `properpcloud` command and the dedicated server distribution expose:

    properpcloud library scan
    properpcloud library status
    properpcloud library search "needle"
    properpcloud-server serve

HTTP endpoints:

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | mount, catalog, and live pCloud connectivity health |
| `GET /api/v1/library/status` | scan/degraded status |
| `POST /api/v1/library/scan` | start one background incremental scan |
| `GET /api/v1/library/browse?parent=...` | list direct catalog children |
| `GET /api/v1/library/search?q=...` | search filename/title/artist/album/genre |
| `GET /api/v1/library/duplicates` | group duplicate audio by SHA-256 fingerprint |
| `GET /api/v1/library/node?id=...` | load one stable node |
| `POST /api/v1/library/stream-link?id=...` | mint a short-lived local stream ticket |
| `POST /api/v1/pcloud/folders/create?parentId=...&name=...` | create a pCloud folder through the REST client |

The stream endpoint implements single byte ranges so Media3/mpv can seek against mounted/cache-backed bytes.

## 6. systemd user services

Copy the units in `packaging/systemd/` to `~/.config/systemd/user/`, install the mount and server launchers under `~/.local/bin/`, then enable the mount and server units. The checked-in units are templates; review paths and the reverse-proxy topology for the target host before enabling them.

The mount unit restarts rclone on failure. The catalog's SQLite state remains independent from mount lifecycle, which is what makes degraded/offline browsing possible.
