# nine-core

The code shared by NineTailsFlip's apps for Android phones with a keypad and no touchscreen.

[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

Every [NineTailsFlip](https://github.com/NineTailsFlip) app includes it as a git submodule:
[TG9](https://github.com/NineTailsFlip/tg9), [G9](https://github.com/NineTailsFlip/g9),
[K9](https://github.com/NineTailsFlip/k9), our forks of the Nine suite
([L9](https://github.com/NineTailsFlip/l9), [C9](https://github.com/NineTailsFlip/c9),
[P9](https://github.com/NineTailsFlip/p9), [M9](https://github.com/NineTailsFlip/m9)), and the
system apps of our LineageOS ([Setup](https://github.com/NineTailsFlip/setup9),
[Settings](https://github.com/NineTailsFlip/settings9), [Lock](https://github.com/NineTailsFlip/lock9)).

## Modules

| Module | Package | What it is | Origin |
|---|---|---|---|
| `core/` (`:core`) | `life.furrer.nine.core` | The Nine suite's UI: one column of plain text on black, the D-pad list, the numbered `*` menu, the hint bar, Settings, backup, the manual | [Nine suite](https://git.sr.ht/~timofurrer/nine) at `ab73f97`, **unchanged** |
| `contacts/` (`:contacts`) | `life.furrer.nine.contacts` | Contacts screens and provider access, used by C9, P9, M9 | Nine suite at `ab73f97`, **unchanged** |
| `kit/` (`:kit`) | `dev.g9.kit` | MediaStore photos and videos (folders, trash), day-grouped rows and day jumps, thumbnails, multi-select marks, a zoomable picture view; used by G9 and TG9 | ours |
| `update/` (`:update`) | `io.github.ninetailsflip.update` | Updates from GitHub Releases; `SetupChoices`, the look chosen in the phone's first-time setup | ours |

`core/` and `contacts/` stay exactly as upstream, so later versions can be copied over them.
`KNOWN_ISSUE_T9_IME.md` is upstream's note on the T9 input method capturing keys — every app that
types needs to know it.

### `update`

Once a day, when the app's main screen opens, it reads the latest release's `update.json`
(written by the release workflow in [NineTailsFlip/.github](https://github.com/NineTailsFlip/.github)).
If there is a newer version, it offers it once; OK downloads the APK for the phone's ABI, checks
its SHA-256 and hands it to Android's installer, which checks the signature. Settings → Updates
turns the check off or runs it now. It adds the `INTERNET` and `REQUEST_INSTALL_PACKAGES`
permissions.

`SetupChoices` adopts the font and hint bar the phone's first-time setup serves
(`io.github.ninetailsflip.setup.choices`), once per revision, into the app's own Settings → Theme.

## Usage

Add the submodule and the modules you need:

```bash
git submodule add https://github.com/NineTailsFlip/nine-core.git nine-core
```

```kotlin
// settings.gradle.kts — the modules depend on each other by these names
include(":core")
project(":core").projectDir = file("nine-core/core")
include(":kit")
project(":kit").projectDir = file("nine-core/kit")
include(":update")
project(":update").projectDir = file("nine-core/update")
// C9, P9, M9:
include(":contacts")
project(":contacts").projectDir = file("nine-core/contacts")
```

An app with `:contacts` also needs `android.experimental.enableTestFixturesKotlinSupport=true` in
`gradle.properties`. Updates take two lines:

```kotlin
Updates.init(this, "NineTailsFlip/<repo>")   // Application.onCreate
updatesSection(screen, this, ::refresh)      // SettingsActivity.buildAppSections
```

After a change here, push it, then in each app: `git -C nine-core pull` and commit the new
pointer.

## Building

Requirements: JDK 17+, Android SDK (platform 34), `sdk.dir` in `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug
```

## Updating from upstream

When `~timofurrer/nine` moves on, copy its `core/` and `contacts/` over these folders as they
are, run the tests, and bump the submodule in each app.

## Contributing

Issues and pull requests are welcome; see
[CONTRIBUTING](https://github.com/NineTailsFlip/.github/blob/main/CONTRIBUTING.md). Changes to
`core/` and `contacts/` belong upstream: <https://todo.sr.ht/~timofurrer/nine>.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). `core/` and `contacts/` are GPL-3.0, © Timo Furrer
and the Nine contributors.
