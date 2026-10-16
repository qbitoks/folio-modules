package app.folio.tts

import android.content.Context

/** The voice the user marked as default per language (`ru` → voice name), used when an app names none. */
class VoiceDefaults(context: Context) {
    private val prefs = context.getSharedPreferences("voice_defaults", Context.MODE_PRIVATE)

    fun get(language: String): String? = prefs.getString(language, null)

    fun set(language: String, voice: String) = prefs.edit().putString(language, voice).apply()
}
