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
- For building: the project's dev container ([base-devcontainer](https://github.com/glashoff/devcontainer-sandbox)),
  or Podman.

## Building

```sh
cp voice.properties.example voice.properties   # your server address and user, not in the repository
./build.sh                                      # -> app/build/outputs/apk/release/app-release.apk
./install.sh                                    # on the host: stop the app, install the APK, start it again
```

### Installing over Wi-Fi (wireless debugging)

`adb install` needs a connection to the phone. Without a cable, use wireless debugging (Android 11+):

1. On the phone: *Settings → Developer options → Wireless debugging* (enable developer options first by tapping
   *Build number* seven times). Phone and computer must be in the same network.
2. First time only, pair: tap *Pair device with pairing code* and run on the computer
   `adb pair <IP>:<pairing port>` with the code shown on the phone.
3. Connect: the *Wireless debugging* screen shows *IP address & port*; run
   `adb connect <IP>:<port>`. Check with `adb devices`.

The port changes whenever wireless debugging is switched off and on or the phone reconnects to the network, so run
`adb connect` again with the port currently shown on the phone.

`build.sh` fetches the sherpa-onnx library once, checking its SHA-256, and runs Gradle:

- **In the dev container** (`.devcontainer/`, started with `devcontainer-start`): the shared sandbox image plus a
  JDK. The pinned Android SDK is installed on the first start into the Docker volume `voice-android-sdk`; Gradle's
  cache and the signing key are kept in `voice-android-gradle` and `voice-android-keys`. The container cannot reach
  the local network, so run `adb` on the host.
- **On a plain host**: in a pinned Podman image (`build-env/Containerfile`), with the Podman volumes of the same
  names.

Keep the volume `voice-android-keys`, or updates cannot be installed over the existing app. Both setups must hold
the same `debug.keystore`.

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
