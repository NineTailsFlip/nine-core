# nine-core

The code shared by [TG9](https://github.com/NineTailsFlip/tg9) (Telegram on 9 keys) and
[G9](https://github.com/NineTailsFlip/g9) (Gallery on 9 keys), two Android apps for phones with a
physical keypad and no touchscreen.

- `core/` (`:core`) is the [Nine suite](https://git.sr.ht/~timofurrer/nine)'s shared UI module:
  one column of plain text on black, the D-pad list, the numbered `*` menu, the hint bar,
  Settings, backup and the manual. It is copied **unchanged** from `~timofurrer/nine` at commit
  `ab73f97`. Keep it that way, so later versions can be copied over it.
- `kit/` (`:kit`, `dev.g9.kit`) is our own:
  - reading photos and videos through MediaStore, including folders and the trash;
  - newest-first rows under day headers, and day jumps;
  - thumbnail rows and a thumbnail loader that skips rows scrolled far away;
  - marks for multi-select;
  - a zoomable picture view.

## Using it in an app

The apps include this repository as a git submodule at `nine-core/` and add both modules in their
`settings.gradle.kts`. The modules must keep the names `:core` and `:kit`, because `:kit`
depends on `project(":core")`.

```kotlin
include(":core")
project(":core").projectDir = file("nine-core/core")
include(":kit")
project(":kit").projectDir = file("nine-core/kit")
```

```bash
git submodule add https://github.com/NineTailsFlip/nine-core.git nine-core
```

After changing something here, commit and push it, then update the submodule in each app:
`git -C nine-core pull`, then commit the new `nine-core` pointer.

## Working on it alone

It builds and tests on its own. You need JDK 17+ and the Android SDK (platform 34), with
`sdk.dir` set in `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug
```

## License

GPL-3.0-or-later (see `LICENSE`), as `core/` is GPL-3.0.
