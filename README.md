# nine-core

The code shared by NineTailsFlip's apps for Android phones with a physical keypad and no
touchscreen: [TG9](https://github.com/NineTailsFlip/tg9) (Telegram), [G9](https://github.com/NineTailsFlip/g9)
(Gallery), and our forks of the Nine suite, [L9](https://github.com/NineTailsFlip/l9),
[C9](https://github.com/NineTailsFlip/c9), [P9](https://github.com/NineTailsFlip/p9) and
[M9](https://github.com/NineTailsFlip/m9).

- `core/` (`:core`) is the [Nine suite](https://git.sr.ht/~timofurrer/nine)'s shared UI module:
  one column of plain text on black, the D-pad list, the numbered `*` menu, the hint bar,
  Settings, backup and the manual. It is copied **unchanged** from `~timofurrer/nine` at commit
  `ab73f97`. Keep it that way, so later versions can be copied over it.
- `contacts/` (`:contacts`) is the Nine suite's shared contacts screens and provider access,
  used by C9, P9 and M9. It is also copied **unchanged** from `~timofurrer/nine` at `ab73f97`.
- `KNOWN_ISSUE_T9_IME.md` is upstream's note on the T9 input method capturing keys. Every app
  that types needs to know it.
- `kit/` (`:kit`, `dev.g9.kit`) is our own:
  - reading photos and videos through MediaStore, including folders and the trash;
  - newest-first rows under day headers, and day jumps;
  - thumbnail rows and a thumbnail loader that skips rows scrolled far away;
  - marks for multi-select;
  - a zoomable picture view.

## Using it in an app

The apps include this repository as a git submodule at `nine-core/` and add the modules they need
in their `settings.gradle.kts`. The modules must keep the names `:core`, `:contacts` and `:kit`,
because they depend on each other by those names.

```kotlin
include(":core")
project(":core").projectDir = file("nine-core/core")
include(":kit")
project(":kit").projectDir = file("nine-core/kit")
// C9, P9, M9:
include(":contacts")
project(":contacts").projectDir = file("nine-core/contacts")
```

An app that includes `:contacts` also needs `android.experimental.enableTestFixturesKotlinSupport=true`
in its `gradle.properties`.

```bash
git submodule add https://github.com/NineTailsFlip/nine-core.git nine-core
```

After changing something here, commit and push it, then update the submodule in each app:
`git -C nine-core pull`, then commit the new `nine-core` pointer.

## Updating from upstream

When `~timofurrer/nine` moves on, copy its `core/` and `contacts/` over these folders as they
are. Then run the tests and bump the submodule in each app.

## Working on it alone

It builds and tests on its own. You need JDK 17+ and the Android SDK (platform 34), with
`sdk.dir` set in `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug
```

## License

GPL-3.0-or-later (see `LICENSE`), as `core/` and `contacts/` are GPL-3.0 (Timo Furrer).
