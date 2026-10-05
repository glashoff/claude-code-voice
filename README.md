# Voice app for Claude Code

An Android app for talking to [Claude Code](https://docs.claude.com/en/docs/claude-code) on another machine by
voice: you speak, the app sends your words over SSH to Claude Code running in one of your projects, and reads the
answer aloud. Made for working hands-free, e.g. with a headset while walking around.

Nothing has to be installed on the server besides `sshd` and `claude`. All commands are built in the app.

## Features

- **Speech recognition**: one continuous on-device session (Android 13+, no beeps between utterances), the plain
  Android recognizer, or whisper.cpp in the app. German and English, optional automatic switching.
- **Sending**: what you say collects in a draft that is sent after a pause or a keyword. Say *warte* to hold it,
  *löschen* to drop it; the draft can also be edited with the keyboard.
- **Speech output**: any installed Android TTS engine, or the German Piper voice *Thorsten* synthesized in the app
  (sherpa-onnx, downloaded once and checksum-verified). Engine and voice are remembered per reply language.
  Speaking interrupts the output; it resumes after you are done.
- **Permissions by voice**: Claude may read freely; every action that changes something (editing files, running
  commands) is read aloud and needs your answer: *ja* / *bestätige*, *alles erlauben* (until the current task is
  done) or *nein*. Anything else counts as no and goes to Claude as a remark.
- **Robust connection**: Claude runs detached from the SSH connection. If the phone loses the network or the app is
  closed, Claude keeps working on the server and waits at the next question; the app reconnects, catches up on what
  happened and asks again.
- **Dev container projects**: projects with a `.devcontainer/devcontainer.json` (see
  [base-devcontainer](https://github.com/glashoff/devcontainer-sandbox)) run Claude inside the project's container;
  the app starts the container if needed.
- **Voice menu** (say *Menü*): send pause, keyword, speed, language, voice, TTS engine, model, project, sessions,
  thinking sound and more. Say *Hilfe* in the menu for the full list.
- Headset microphone (Bluetooth call mode), detection of a muted headset, works with the screen locked, the chat
  survives app restarts.

## How it works

```
phone ──SSH──> server ──(optional) docker exec──> project container
                 │
                 └─ claude -p --input-format stream-json --output-format stream-json
                      stdin  <- named pipe  ~/.cache/voice-app/<run>/in
                      stdout -> file        ~/.cache/voice-app/<run>/out.jsonl
```

- Each conversation is one detached Claude process. A sleeping helper keeps its input pipe open, so Claude never
  sees the end of its input between messages; stopping the helper ends Claude.
- The app writes one JSON line per message into the pipe and follows the output file with `tail -f` from the byte
  position it has processed. After a lost connection it continues from there.
- Permission requests use Claude Code's `--permission-prompt-tool stdio` control protocol.
- While a run exists, a script `stop-voice-claude.sh` lies in the project folder (ignored locally via
  `.git/info/exclude`). Run it on the computer to stop the app's Claude before continuing the conversation there,
  so that two instances do not work on the same session.

## Requirements

- Android 10 or newer (continuous recognition: Android 13+); built for arm64.
- On the server: `sshd` with public key login, Claude Code (`claude` on the `PATH`, `~/.local/bin` is added),
  GNU coreutils (`tail --pid`), `setsid`, `mkfifo`. For container projects: Docker and the dev container setup.
- For building: Podman.

## Building

```sh
cp voice.properties.example voice.properties   # your server address and user, not in the repository
./build.sh                                      # -> app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

`build.sh` builds in a pinned container image (`build-env/Containerfile`) and fetches the sherpa-onnx library
once, checking its SHA-256. The signing key is kept in the Podman volume `voice-android-keys`; keep that volume, or
updates cannot be installed over the existing app.

## Setup

1. Open the settings (gear icon) → *Verbindung*: server, port, user, project folder.
2. Copy the app's public key shown there into `~/.ssh/authorized_keys` on the server.
3. On the first connection the server's host key is pinned; a changed key is refused.
4. Choose a project in the quick settings below the status bar and start talking.

## Security notes

- The app holds an SSH key that logs in as your user. Restrict it on the server as you see fit (e.g. a dedicated
  user); the app does not depend on a forced command.
- Changes need your confirmation, but *alles erlauben* allows everything until the current task ends, including
  actions of subagents.
- In dev container projects Claude can only change that project; elsewhere it acts as your user.

## Third-party components

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) with the Piper voice
  [Thorsten](https://github.com/thorstenMueller/Thorsten-Voice) (CC0)
- [JSch](https://github.com/mwiede/jsch) (BSD-style), [Bouncy Castle](https://www.bouncycastle.org/) (MIT-style),
  [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) (Apache 2.0)
- Jetpack Compose and AndroidX (Apache 2.0)

## License

GNU General Public License v3.0, see [LICENSE](LICENSE).
