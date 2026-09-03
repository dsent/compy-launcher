package toys.compy.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CardWarningKeyTest {
    @Test
    fun yAndNActOnlyWhenReleased() {
        assertEquals(
            CardWarningKeyAction.IGNORE,
            cardWarningKeyAction(KeyEvent.KEYCODE_Y, KeyEvent.ACTION_DOWN, false),
        )
        assertEquals(
            CardWarningKeyAction.CONTINUE,
            cardWarningKeyAction(KeyEvent.KEYCODE_Y, KeyEvent.ACTION_UP, false),
        )
        assertEquals(
            CardWarningKeyAction.OPEN_MAINTENANCE,
            cardWarningKeyAction(KeyEvent.KEYCODE_N, KeyEvent.ACTION_UP, false),
        )
    }

    @Test
    fun canceledAndUnrelatedKeysAreIgnored() {
        assertEquals(
            CardWarningKeyAction.IGNORE,
            cardWarningKeyAction(KeyEvent.KEYCODE_Y, KeyEvent.ACTION_UP, true),
        )
        listOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB,
        ).forEach { keyCode ->
            assertEquals(
                CardWarningKeyAction.IGNORE,
                cardWarningKeyAction(keyCode, KeyEvent.ACTION_UP, false),
            )
        }
    }
}
