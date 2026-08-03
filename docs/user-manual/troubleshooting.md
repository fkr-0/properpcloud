# Troubleshooting

## Start with the demo library

If the demo source cannot browse, queue, and play, the problem is local. If demo playback works but pCloud does not, focus on account region, network, and provider permissions.

## Linux: mpv does not start

```bash
command -v mpv
mpv --no-config --version
make desktop-smoke
```

properpcloud ignores `~/.config/mpv/mpv.conf`; invalid user settings should therefore not affect application playback. The smoke command uses `--ao=null`, so it also works without an available audio device.

## Linux: account cannot be saved

Check for an active Secret Service:

```bash
secret-tool --help >/dev/null
busctl --user list | grep -E 'secret|keyring|kwallet'
```

Minimal window-manager sessions may need to start a keyring daemon. properpcloud does not fall back to an unencrypted token file.

## Linux: media keys do not work

Verify MPRIS registration while the application is running:

```bash
busctl --user list | grep org.mpris.MediaPlayer2.properpcloud
```

MPRIS depends on a session D-Bus. Playback itself remains functional without it.

## pCloud rejects valid credentials

Confirm the selected region. European accounts use the European API host; other accounts use the United States host. The application intentionally does not spray credentials across both endpoints.

The direct-login result code narrows the next step without revealing provider response text:

| Code | Meaning | Action |
| --- | --- | --- |
| `2000` | Generic login failure. pCloud does not identify whether the email/password is wrong. The original account region is also mandatory. | Re-enter the credentials, use the password-reveal control to check for a typo, and verify Europe versus United States. |
| `4000` | Too many login attempts from the current IP address. | Stop retrying and wait before another attempt. |
| `5000` | Provider internal error. | Retry later. |

Accounts requiring a two-factor challenge may need OAuth because pCloud's public direct-login protocol does not document an interactive challenge response.

If the account was created through Google, Apple, or Facebook, the social-provider password is not a pCloud password. Use pCloud's **Forgot password** flow for that email address to create a regular pCloud password before trying direct sign-in.

## A folder appears incomplete

Recursive scans expose omissions when a child folder cannot be listed or the safety bound is reached. Inspect the status message instead of assuming the result is complete.

## Queue restores without some tracks

Queue entries refer to stable provider nodes. Entries are skipped during restoration when the source is disconnected, the file was deleted, or permission was removed. Reconnect the source and rebuild the queue when appropriate.

## Collect a safe diagnostic

```bash
git rev-parse HEAD
java -version
mpv --no-config --version | head -n 3
make desktop-test
make desktop-smoke
```

Do not include Secret Service output, provider tokens, signed URLs, or private filenames unless they are essential and intentionally redacted.
