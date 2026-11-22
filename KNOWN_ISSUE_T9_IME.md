# Known issue: digit keys type literal digits instead of cycling letters

Referenced from `core/src/main/java/life/furrer/nine/core/ui/SearchField.kt` and
`c9/app/src/main/java/life/furrer/c9/calendar/EventEditActivity.kt` /
`c9/app/src/main/java/life/furrer/c9/contacts/ContactEditActivity.kt`.

## Symptom

In any text field across the suite (search boxes, rename dialogs, C9's contact/event
editors), pressing a digit key (e.g. `2`) always types the literal digit `2` first,
committed asynchronously a few hundred ms to ~2s after the physical press — never the
first letter on that key (`a`), and multi-tap/long-press never resolves to the digit as a
*fallback* the way a classic 12-key feature phone works. This holds regardless of the
field's `inputType` (`TYPE_CLASS_TEXT` alone, with `TYPE_TEXT_VARIATION_FILTER`, with
`TYPE_TEXT_FLAG_NO_SUGGESTIONS` — all tried live, all identical).

## Investigation (2026-08-03, on the sr.ht test device)

- Confirmed live in L9's Browse-all search field: pressing `2` shows nothing for roughly a
  second, then the field's text becomes `2`. The async delay (not an immediate, synchronous
  character insert) means *some* IME/input-method layer is processing the key, not just a
  plain synchronous `KeyListener` reacting to the raw event.
- Varying the field's `inputType` (down to bare `InputType.TYPE_CLASS_TEXT`, no filter/no
  suggestions flags) made no difference — ruled out an app-level `inputType` misconfiguration.
- `adb shell dumpsys input` shows the physical keypad as `Device 3: mtk-kpd`, `Sources:
  KEYBOARD | DPAD`, `KeyboardType: 1` (`InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC` — Android's
  own evdev-capability-based classification correctly sees a 12-key pad, not a full keyboard).
- `adb shell ls /system/usr/keychars/` has no dedicated `mtk-kpd.kcm` — only `Generic.kcm`
  (plus a couple of `Vendor_*` ones for other devices). No per-device key character map was
  ever shipped for this keypad, so it falls back to the platform's generic one.
- `adb shell cat /system/usr/keychars/Generic.kcm`: `type FULL` — "Generic key character map
  for full alphabetic US English PC style external keyboards." This is the character map
  actually in effect for the physical 12-key pad.

## Root cause (best-effort — the actual IME is closed-source/unknown, so this is inference
from the evidence above, not confirmed against its source)

Android's own multi-tap disambiguation (`android.text.method.TextKeyListener`/
`MultiTapKeyListener`) — and, it appears, whatever on-device predictive-input service
handles these keys (the async commit delay implies a real IME, not a synchronous
`KeyListener`) — only enables 12-key letter cycling for a keyboard whose
`KeyCharacterMap.getKeyboardType()` reports `NUMERIC`/`PREDICTIVE`. Because this device's
keypad has no dedicated `.kcm` file and falls back to `Generic.kcm`'s `type FULL`, every
input method on the system sees what looks like a full alphabetic keyboard attached and
never engages multi-tap — it just passes through each key's mapped character (`2` → `'2'`)
like a regular keyboard would. This reproduces in **every app**, not just Nine's — it is a
device/ROM firmware defect (a missing vendor key character map for `mtk-kpd`, e.g. a
`mtk-kpd.kcm` declaring `type NUMERIC` with a proper 12-key layout), not a bug in any of
L9/C9/P9's code.

## Decision

Not working around this at the application level. `core/SearchField.kt`'s existing design
(§5.4: "never reimplement T9 multi-tap") is a deliberate choice to rely on the OS/hardware
for predictive text rather than hand-rolling a multi-tap key listener in Kotlin — and that
choice stands even though it doesn't hold up on *this particular* device/firmware. The
correct fix is a vendor-supplied `NUMERIC`-type key character map for the keypad, which is
outside this repo's scope. Digit keys in text fields across the suite will keep typing their
literal digit until that's fixed at the device/ROM level.
