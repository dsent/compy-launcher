package toys.compy.launcher

import android.view.KeyEvent

internal enum class CardWarningKeyAction {
    CONTINUE,
    OPEN_MAINTENANCE,
    IGNORE,
}

internal fun cardWarningKeyAction(
    keyCode: Int,
    action: Int,
    canceled: Boolean,
): CardWarningKeyAction {
    if (action != KeyEvent.ACTION_UP || canceled) {
        return CardWarningKeyAction.IGNORE
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_Y -> CardWarningKeyAction.CONTINUE
        KeyEvent.KEYCODE_N -> CardWarningKeyAction.OPEN_MAINTENANCE
        else -> CardWarningKeyAction.IGNORE
    }
}
