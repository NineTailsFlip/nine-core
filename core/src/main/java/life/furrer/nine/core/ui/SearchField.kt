package life.furrer.nine.core.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText

private const val LEAKED_CHAR_WINDOW_MS = 900L

/** A live search `EditText` that gets the device's hardware predictive T9 IME for free, exactly
 *  like any other app's text field (§5.4: never reimplement T9 multi-tap). Encapsulates the two
 *  gotchas L9 learned the hard way (`AppPickerActivity.kt`, comments at lines ~50-67 and
 *  ~214-260) so every C9 search field gets them for free too:
 *
 *  1. **The field must not be focusable until a real T9 key is pressed.** A lone focusable view
 *     in a new window is auto-focused by Android, which establishes an IME connection
 *     immediately — and then the keypress that *opened* the screen leaks into it.
 *  2. **Discard the first text change within [LEAKED_CHAR_WINDOW_MS] of window focus.** The IME
 *     processes keys independently of Activity dispatch; a tap that opened the screen still
 *     shows up as a committed character 300-450ms later, after our own `dispatchKeyEvent` never
 *     saw it. It cannot be intercepted at the key-event level.
 *
 *  Hosting Activities wire this into their own `dispatchKeyEvent` override:
 *  ```kotlin
 *  override fun onWindowFocusChanged(hasFocus: Boolean) {
 *      super.onWindowFocusChanged(hasFocus)
 *      if (hasFocus) searchField.onWindowFocusGained()
 *  }
 *  override fun onPause() {
 *      super.onPause()
 *      searchField.onPause()
 *  }
 *  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
 *      if (event.keyCode == KeyEvent.KEYCODE_BACK) {
 *          if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
 *          onBackNotConsumedByIme()
 *          return true
 *      }
 *      if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
 *          if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) {
 *              // this Activity's own Up/Down/OK/Left/Right/`*`/`#`/call-key handling
 *          }
 *          return true
 *      }
 *      return super.dispatchKeyEvent(event)
 *  }
 *  ```
 */
class SearchField(context: Context) {

    val editText: EditText = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_FILTER or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
        background = null
        // Not focusable until the first real T9 key press (see handleKeyEvent) — a lone
        // focusable view in a new window gets auto-focused by Android otherwise.
        isFocusable = false
        isFocusableInTouchMode = false
        addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (!firstTextChangeHandled) {
                        firstTextChangeHandled = true
                        val sinceFocus = System.currentTimeMillis() - focusGainedAtMillis
                        if (!s.isNullOrEmpty() && sinceFocus < LEAKED_CHAR_WINDOW_MS) {
                            s.clear()
                            return
                        }
                    }
                    onQueryChanged(s?.toString() ?: "")
                    // Query back to empty (backspaced out, or programmatically cleared) — revert
                    // to the pre-activation inert state so `*`/`#` mean this screen's own
                    // shortcuts again rather than whatever the field/IME would otherwise do with
                    // them. Without this, real focus (and the IME's live connection to it) would
                    // otherwise persist for the rest of the screen's life once ever gained, even
                    // with nothing left typed.
                    if (s.isNullOrEmpty()) deactivate()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            },
        )
    }

    var onQueryChanged: (String) -> Unit = {}

    /** Runs once the query is back to empty and the field inert again. Unfocusing the field
     *  alone (see [deactivate]) doesn't stop this device's hardware T9 IME from swallowing `*`/`#`
     *  once it has had a live connection to it, so callers that reserve `*`/`#` (§5.4/§7.4) set
     *  this to [relaunch] themselves — see that function for why only a fresh window works. Left a
     *  no-op for callers (like EventSearchActivity) that don't give the search field's own keys
     *  any app-level meaning, so they don't pay for a restart they don't need. */
    var onDeactivated: () -> Unit = {}

    /** Guards against the key-up of whichever press opened this screen leaking in after the
     *  field already has focus. */
    private val seenKeyDowns = mutableSetOf<Int>()
    private var focusGainedAtMillis: Long = 0
    private var firstTextChangeHandled = false

    fun onWindowFocusGained() {
        focusGainedAtMillis = System.currentTimeMillis()
    }

    fun onPause() {
        seenKeyDowns.clear()
    }

    fun clear() {
        editText.setText("")
        deactivate()
    }

    /** Returns the field to the same not-focusable-until-a-real-key-press state it starts in
     *  (see the class doc's gotcha #1) — called whenever the query goes back to empty, so `*`/`#`
     *  reliably fall through to the hosting Activity's own reserved-key handling instead of
     *  possibly being swallowed by a still-live IME connection to this field (§5.4/§7.4: the
     *  search box should only ever "own" `*`/`#` while there's actually something typed). */
    private fun deactivate() {
        editText.isFocusable = false
        editText.isFocusableInTouchMode = false
        editText.clearFocus()
        // Posted rather than called inline: this runs from inside the EditText's own
        // TextWatcher, itself invoked mid-dispatch of the key that emptied the field — recreate()
        // tearing down the view hierarchy from that same call stack risks reentrancy, so it's
        // deferred to run once the current key event has fully finished being handled.
        editText.post { onDeactivated() }
    }

    val query: String get() = editText.text?.toString() ?: ""

    /** For `KEYCODE_BACK` specifically, ahead of [handleKeyEvent] — gives the key to the IME
     *  first via [dispatchToSuper] (pass a lambda that calls `super.dispatchKeyEvent(event)` on
     *  the hosting Activity) so it can clear any composing/predictive text of its own.
     *  @return true if the IME consumed it (nothing more to do) or the event isn't
     *  `ACTION_DOWN`; false only when the caller should run its own not-consumed-by-IME back
     *  action (e.g. clear the search field, or leave the screen if it was already empty). */
    fun handleBack(event: KeyEvent, dispatchToSuper: (KeyEvent) -> Boolean): Boolean {
        if (dispatchToSuper(event)) return true
        return event.action != KeyEvent.ACTION_DOWN
    }

    /** Every other key. [reservedKeys] never reach the field/IME at all — Up/Down/OK are always
     *  the caller's own list navigation (a single-line search box has no use for them), plus
     *  whatever else the caller reserves (§5.4: C9 additionally reserves `*`/`#` so the menu and
     *  favorites toggle stay ours while typing).
     *  @return true if this call fully handled the event and the caller must return true
     *  (consumed) without doing anything else *except*, when [event] is a reserved key on
     *  `ACTION_DOWN`, running its own handling for that key; false if the caller must fall
     *  through to `super.dispatchKeyEvent(event)` itself — required, since a helper object can't
     *  make that super call on the Activity's behalf. */
    fun handleKeyEvent(event: KeyEvent, reservedKeys: Set<Int>): Boolean {
        if (event.keyCode in reservedKeys) return true

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                seenKeyDowns.add(event.keyCode)
                // Only a genuine T9 key (never a reserved nav key, handled above) activates the
                // search field — it stays unfocused/inert while the user is just browsing.
                if (!editText.isFocusable) {
                    editText.isFocusable = true
                    editText.isFocusableInTouchMode = true
                    editText.requestFocus()
                }
            }
            KeyEvent.ACTION_UP -> if (!seenKeyDowns.remove(event.keyCode)) return true
        }
        return false
    }
}
