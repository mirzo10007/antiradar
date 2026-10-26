package uz.antiradar.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Ovozli va tebranish orqali ogohlantirish. O'zbek ovozi bo'lmasa, signal beriladi. */
class Alerts(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale("uz"))
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun say(text: String, urgent: Boolean = false) {
        if (urgent) vibrate()
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        } else {
            tone?.startTone(
                if (urgent) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2,
                if (urgent) 600 else 250,
            )
        }
    }

    private fun vibrate() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 300, 150, 300), -1)
        }
    }

    fun release() {
        tts.shutdown()
        tone?.release()
    }
}
