# User manual

This manual covers daily use of properpcloud on Android and Linux. The two clients share folder, queue, sorting, progress, source identity, and metadata semantics while using platform-native playback and credential facilities.

## Fast path

1. Install the client for your platform.
2. Start with the built-in demo library to verify browsing, queuing, playback, and resume behavior.
3. Open the account action and connect pCloud using the correct account region.
4. Double-click or tap a folder to browse it.
5. Use **Play folder**, **Play next**, or **Append** to build the queue.
6. Use **Show folder** from the queue to return to the current track's containing folder.

## Manual sections

- [Android installation](android-installation.md)
- [Linux installation](linux-installation.md)
- [First run](first-run.md)
- [Library and queue](library-and-queue.md)
- [Playback and progress](playback-and-progress.md)
- [Accounts and security](accounts-and-security.md)
- [Metadata inspection and repair](metadata.md)
- [Troubleshooting](troubleshooting.md)

## Terminology

| Term | Meaning |
| --- | --- |
| Source | A provider-backed or local library, such as pCloud or the demo source. |
| Node | A stable folder or audio-file identity within a source. |
| Queue snapshot | The ordered tracks found when a folder is queued. Later provider changes do not silently rewrite it. |
| Direct link | A short-lived URL resolved immediately before playback. It is not persisted as media identity. |
| Inspection | Read-only provider and embedded metadata shown for diagnosis. |
| Repair plan | A proposed metadata change set that must be reviewed before any write. |
