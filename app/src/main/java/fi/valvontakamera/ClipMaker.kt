package fi.valvontakamera

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Leikkaa tapahtuman videopätkistä yhden 720p-klipin Telegramia varten
 * (Telegram-botin tiedostoraja on 50 Mt). Kutsu pääsäikeestä.
 */
object ClipMaker {
    fun make(ctx: Context, segs: List<Seg>, cs: Long, ce: Long, out: File, done: (Boolean, String?) -> Unit) {
        try {
            val items = ArrayList<EditedMediaItem>()
            for (s in segs) {
                val a = (cs - s.start).coerceAtLeast(0)
                val b = (ce - s.start).coerceAtMost(s.end - s.start)
                if (b - a < 300) continue
                val mi = MediaItem.Builder()
                    .setUri(Uri.fromFile(s.file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(a)
                            .setEndPositionMs(b)
                            .build()
                    ).build()
                val effects = Effects(emptyList<AudioProcessor>(), listOf<Effect>(Presentation.createForHeight(720)))
                items.add(EditedMediaItem.Builder(mi).setEffects(effects).build())
            }
            if (items.isEmpty()) { done(false, "ei videota"); return }
            out.delete()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(2_500_000).build()
                        ).build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done(true, null)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult,
                                         exportException: ExportException) {
                        done(false, exportException.errorCodeName + " " + (exportException.message ?: ""))
                    }
                })
                .build()
            val comp = Composition.Builder(listOf(EditedMediaItemSequence(items))).build()
            transformer.start(comp, out.absolutePath)
        } catch (t: Throwable) {
            done(false, t.message)
        }
    }
}
