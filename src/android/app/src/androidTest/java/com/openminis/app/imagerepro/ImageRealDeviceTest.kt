package com.openminis.app.imagerepro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.provider.ImageBudget
import com.openminis.app.tools.ReadImageTool
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The image-pipeline facts a JVM test cannot verify, on a real Android
 * decoder (the production AndroidImageCodec = BitmapFactory / Bitmap).
 * Not run by the automated suite (it needs a device). Run by a person on an emulator or a test device:
 *
 *   cd src/android && ./gradlew :app:connectedGithubDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.openminis.app.imagerepro.ImageRealDeviceTest
 *
 * Caution: connectedAndroidTest installs and, on completion, may UNINSTALL the
 * app under test (see memory note android_canvas_bitmap_test_pitfalls). Use an
 * emulator or a device whose app data does not matter.
 *
 * (A) tests pin today's behaviour; (B) are @Ignore("known gap …").
 */
@RunWith(AndroidJUnit4::class)
class ImageRealDeviceTest {

    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun encode(bmp: Bitmap, format: Bitmap.CompressFormat, q: Int = 90): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(format, q, it) }.toByteArray()

    private fun solid(w: Int, h: Int, color: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888) =
        Bitmap.createBitmap(w, h, config).apply { eraseColor(color) }

    /** EXIF APP1 (little-endian TIFF, one entry 0x0112 = [orientation]) spliced after SOI. */
    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, orientation.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun size(b: ByteArray): Pair<Int, Int> =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(b, 0, b.size, it) }.let { it.outWidth to it.outHeight }

    /** (A) P-a6: a 4032×3024 photo tagged EXIF orientation 6 is sized at its STORED orientation (landscape). */
    @Test fun downscale_ignores_exif_orientation() {
        val photo = withExif(encode(solid(4032, 3024, Color.GRAY, Bitmap.Config.RGB_565), Bitmap.CompressFormat.JPEG), 6)
        val out = ImageBudget.downscaleForModel(photo)!!
        assertEquals(2000 to 1500, out.width to out.height)
        assertEquals(2000 to 1500, size(out.bytes))
    }

    /** (B) P-a6: the model copy should be upright (1500×2000). */
    @Ignore("known gap P-a6: BitmapFactory path ignores EXIF orientation")
    @Test fun downscale_applies_exif_orientation() {
        val photo = withExif(encode(solid(4032, 3024, Color.GRAY, Bitmap.Config.RGB_565), Bitmap.CompressFormat.JPEG), 6)
        assertEquals(1500 to 2000, ImageBudget.downscaleForModel(photo)!!.let { it.width to it.height })
    }

    /** (A) P-a6: compressBytes always writes JPEG, so a transparent PNG comes back with a black (0,0) pixel. */
    @Test fun transparent_png_through_compressBytes_turns_black() {
        val png = encode(solid(3000, 3000, Color.TRANSPARENT), Bitmap.CompressFormat.PNG)
        val jpeg = ImageBudget.compressBytes(png)
        val px = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size).getPixel(0, 0)
        assertEquals(0, Color.red(px) + Color.green(px) + Color.blue(px))
    }

    /** (A) S1/S5 sizes on the real decoder: downscale (round), screenshot resize and read_image (floor). */
    @Test fun real_decoder_sizes() {
        val longPng = encode(solid(2760, 21891, Color.BLUE, Bitmap.Config.RGB_565), Bitmap.CompressFormat.PNG)
        assertEquals(252 to 2000, ImageBudget.downscaleForModel(longPng)!!.let { it.width to it.height })
        val tallJpeg = encode(solid(1080, 32768, Color.GRAY, Bitmap.Config.RGB_565), Bitmap.CompressFormat.JPEG)
        assertEquals(65 to 2000, size(ImageBudget.resizeJpegToMaxEdge(tallJpeg, 2000)!!))
        val file = java.io.File(cache, "imgreal_long.png").apply { writeBytes(longPng) }
        val r = ReadImageTool.imageResult(file, "/var/minis/workspace/long.png", "t")
        assertEquals(252 to 2000, size(r.imageData!!))
        file.delete()
    }
}
