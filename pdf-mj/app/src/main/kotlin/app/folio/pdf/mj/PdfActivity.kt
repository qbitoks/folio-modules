package app.folio.pdf.mj

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.folio.pdf.mj.ui.ReaderScreen

/** Opened by Folio with a book; closes back to it. Volume keys turn pages unless turned off. */
class PdfActivity : ComponentActivity() {
    private val vm: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val request = FolioLink.request(intent)
        if (request == null) {
            finish()
            return
        }
        vm.open(request)
        setContent { ReaderScreen(vm, onClose = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FolioLink.request(intent)?.let {
            vm.flush()
            setIntent(intent)
            recreate()
        }
    }

    override fun onStop() {
        vm.flush()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = pageKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        vm.turn(delta)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (pageKey(keyCode) != null) true else super.onKeyUp(keyCode, event)

    /** −1 / +1 for a page key, null for keys left to the system (volume keys when turned off). */
    private fun pageKey(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> (-1).takeIf { vm.prefs.value.volumeKeys }
        KeyEvent.KEYCODE_VOLUME_DOWN -> 1.takeIf { vm.prefs.value.volumeKeys }
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> -1
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> 1
        else -> null
    }
}
