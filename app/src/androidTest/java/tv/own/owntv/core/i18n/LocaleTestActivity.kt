package tv.own.owntv.core.i18n

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext

/** Host used to verify that the actual Compose LocalContext preserves Activity semantics. */
class LocaleTestActivity : ComponentActivity() {
    @Volatile
    var localizedContext: Context? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("localized_context_test", MODE_PRIVATE)
        prefs.edit().putString("ui_language", "en-US").commit()
        val store = LocaleStore(prefs, null)
        setContent {
            LocalizedContent(store) {
                localizedContext = LocalContext.current
                Box {}
            }
        }
    }
}
