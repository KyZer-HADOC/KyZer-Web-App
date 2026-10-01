package com.kyzer.ytdl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancelFlag {
    @Volatile var cancelled = false
    @Volatile var paused = false
}
class CancelledException : IOException("Cancelled")

data class VideoOption(
    val label: String,       // e.g. "1080p"
    val badge: String,       // e.g. "Full HD"
    val detail: String,      // e.g. "MP4 · 60 fps"
    val sizeBytes: Long,
    val video: VideoStream,
    val audio: AudioStream?, // null = stream already has audio
    val container: String,   // mp4 | mkv
    val height: Int
)

data class Mp3Option(val kbps: Int, val sizeBytes: Long)

object Engine {

    data class Saved(val uri: Uri, val name: String, val mime: String, val size: Long)

    // ---------------------------------------------------------------- options

    private fun isAvc(s: VideoStream): Boolean {
        val c = s.codec ?: ""
        return s.format == MediaFormat.MPEG_4 && (c.isEmpty() || c.startsWith("avc"))
    }

    private fun bestAudio(info: StreamInfo, preferM4a: Boolean): AudioStream? =
        info.audioStreams.filter { it.isUrl }
            .sortedWith(
                compareByDescending<AudioStream> { preferM4a && it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            ).firstOrNull()

    fun videoOptions(info: StreamInfo): List<VideoOption> {
        val audio = bestAudio(info, preferM4a = true)
        val all = (info.videoStreams + info.videoOnlyStreams).filter { it.isUrl && it.height > 0 }
        val out = mutableListOf<VideoOption>()

        for ((_, group) in all.groupBy { it.resolution }) {
            val progressive = group.firstOrNull { !it.isVideoOnly && it.format == MediaFormat.MPEG_4 }
            val chosen: VideoStream
            var mergeAudio: AudioStream? = null
            var container = "mp4"

            if (progressive != null) {
                chosen = progressive
            } else {
                if (audio == null) continue
                val onlyStreams = group.filter { it.isVideoOnly }
                val avc = onlyStreams.filter { isAvc(it) }.maxByOrNull { it.bitrate }
                if (avc != null) {
                    chosen = avc
                } else {
                    chosen = onlyStreams.maxByOrNull { it.bitrate } ?: continue
                    container = "mkv"
                }
                mergeAudio = audio
            }

            val h = chosen.height
            val badge = when {
                h >= 2160 -> "4K"
                h >= 1440 -> "2K"
                h >= 1080 -> "Full HD"
                h >= 720 -> "HD"
                else -> ""
            }
            val fps = if (chosen.fps > 30) " · ${chosen.fps} fps" else ""
            val vBps = chosen.bitrate.toLong()
            val aBps = (mergeAudio?.averageBitrate ?: 0).coerceAtLeast(0) * 1000L
            val size = if (vBps > 0 && info.duration > 0) (vBps + aBps) * info.duration / 8 else 0L

            out += VideoOption(
                label = "${h}p", badge = badge,
                detail = container.uppercase() + fps,
                sizeBytes = size, video = chosen, audio = mergeAudio,
                container = container, height = h
            )
        }
        return out.sortedWith(
            compareByDescending<VideoOption> { it.height }.thenByDescending { it.video.fps }
        )
    }

    fun mp3Options(info: StreamInfo): List<Mp3Option> =
        if (bestAudio(info, false) == null) emptyList()
        else listOf(320, 256, 192, 128, 96, 64).map {
            Mp3Option(it, if (info.duration > 0) it * 1000L * info.duration / 8 else 0L)
        }

    fun loadThumb(info: StreamInfo): Bitmap? {
        val url = info.thumbnails.maxByOrNull { it.height }?.url ?: return null
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            return BitmapFactory.decodeStream(r.body?.byteStream())
        }
    }

    // --------------------------------------------------------------- download

    fun runVideo(
        ctx: Context, info: StreamInfo, o: VideoOption, flag: CancelFlag,
        onProgress: (String, Float?, String) -> Unit
    ): Saved {
        val work = freshWorkDir(ctx)
        val base = sanitize(info.name)
        try {
            val audio = o.audio
            if (audio == null) {
                val f = File(work, "video.mp4")
                download(o.video.content, f, flag) { d, t, sp -> onProgress("Downloading video", frac(d, t), det(d, t, sp)) }
                return save(ctx, f, "$base - ${o.label}.mp4", "video/mp4")
            }
            val v = File(work, "v." + (o.video.format?.suffix ?: "mp4"))
            val a = File(work, "a." + (audio.format?.suffix ?: "m4a"))
            download(o.video.content, v, flag) { d, t, sp -> onProgress("Downloading video", frac(d, t)?.times(0.75f), det(d, t, sp)) }
            download(audio.content, a, flag) { d, t, sp -> onProgress("Downloading audio", frac(d, t)?.let { 0.75f + it * 0.2f }, det(d, t, sp)) }
            onProgress("Merging video + audio", null, "Almost done…")
            val out = File(work, "out." + o.container)
            val args = mutableListOf("-y", "-i", v.path, "-i", a.path, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy")
            if (o.container == "mp4") args += listOf("-movflags", "+faststart")
            args += out.path
            ffmpeg(args, flag)
            val mime = if (o.container == "mp4") "video/mp4" else "video/x-matroska"
            return save(ctx, out, "$base - ${o.label}.${o.container}", mime)
        } finally {
            work.deleteRecursively()
        }
    }

    fun runMp3(
        ctx: Context, info: StreamInfo, o: Mp3Option, flag: CancelFlag,
        onProgress: (String, Float?, String) -> Unit
    ): Saved {
        val work = freshWorkDir(ctx)
        val base = sanitize(info.name)
        try {
            val audio = bestAudio(info, false) ?: throw IOException("No audio stream found")
            val a = File(work, "a." + (audio.format?.suffix ?: "m4a"))
            download(audio.content, a, flag) { d, t, sp -> onProgress("Downloading audio", frac(d, t)?.times(0.85f), det(d, t, sp)) }
            onProgress("Converting to MP3", null, "Almost done…")
            val out = File(work, "out.mp3")
            ffmpeg(
                listOf(
                    "-y", "-i", a.path, "-vn", "-c:a", "libmp3lame", "-b:a", "${o.kbps}k",
                    "-id3v2_version", "3",
                    "-metadata", "title=${info.name}",
                    "-metadata", "artist=${info.uploaderName}",
                    out.path
                ), flag
            )
            return save(ctx, out, "$base - ${o.kbps}kbps.mp3", "audio/mpeg")
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- helpers

    fun fmtSize(b: Long): String = when {
        b <= 0 -> ""
        b >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.2f GB", b / 1e9)
        b >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", b / 1e6)
        else -> String.format(java.util.Locale.US, "%d KB", b / 1000)
    }

    private fun det(done: Long, total: Long, speed: Long): String {
        val sz = if (total > 0) "${fmtSize(done)} / ${fmtSize(total)}" else fmtSize(done)
        return if (speed > 0) "$sz  ·  ${fmtSize(speed)}/s" else sz
    }

    private fun waitIfPaused(flag: CancelFlag) {
        while (flag.paused && !flag.cancelled) Thread.sleep(150)
        if (flag.cancelled) throw CancelledException()
    }

    private fun frac(done: Long, total: Long): Float? =
        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    private fun freshWorkDir(ctx: Context) =
        File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }

    private fun sanitize(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "video" }

    private fun ffmpeg(args: List<String>, flag: CancelFlag) {
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (flag.cancelled) throw CancelledException()
        if (!ReturnCode.isSuccess(session.returnCode)) throw IOException("Conversion failed")
    }

    /** Downloads in 4 MB Range chunks (avoids throttling). Supports pause/resume/cancel. */
    private fun download(url: String, dest: File, flag: CancelFlag, onBytes: (Long, Long, Long) -> Unit) {
        val chunk = 4L * 1024 * 1024
        var pos = 0L
        var total = -1L
        var lastT = System.nanoTime()
        var lastPos = 0L
        val buf = ByteArray(64 * 1024)

        dest.outputStream().use { out ->
            while (total < 0 || pos < total) {
                waitIfPaused(flag)
                lastT = System.nanoTime(); lastPos = pos
                val req = Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$pos-${pos + chunk - 1}")
                    .build()
                httpClient.newCall(req).execute().use { r ->
                    if (r.code == 416) { total = pos; return@use }
                    if (!r.isSuccessful) throw IOException("Server error ${r.code}")
                    val body = r.body ?: throw IOException("Empty response")
                    if (total < 0) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: body.contentLength()
                    }
                    var got = 0L
                    var interrupted = false
                    body.byteStream().use { input ->
                        while (true) {
                            if (flag.cancelled) throw CancelledException()
                            if (flag.paused) {
                                if (r.code == 206) { interrupted = true; break }  // re-request from pos on resume
                                waitIfPaused(flag)
                                lastT = System.nanoTime(); lastPos = pos
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            pos += n
                            got += n
                            val now = System.nanoTime()
                            if (now - lastT > 250_000_000L) {
                                val speed = ((pos - lastPos) * 1_000_000_000L) / (now - lastT)
                                lastT = now; lastPos = pos
                                onBytes(pos, total, speed)
                            }
                        }
                    }
                    if (interrupted) return@use
                    if (r.code != 206) total = pos
                    else if (got == 0L) throw IOException("Connection lost")
                }
            }
        }
        onBytes(pos, pos, 0)
    }

    private fun save(ctx: Context, file: File, name: String, mime: String): Saved {
        if (Build.VERSION.SDK_INT < 29) {
            // Android 8/9: write straight into Downloads/KyZer YouBe
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "KyZer YouBe"
            ).apply { mkdirs() }
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            val latch = CountDownLatch(1)
            var scanned: Uri? = null
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime)) { _, u ->
                scanned = u
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            return Saved(scanned ?: Uri.fromFile(dest), name, mime, dest.length())
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KyZer YouBe")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create file in Downloads")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Could not write file")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(uri, name, mime, file.length())
    }
}
