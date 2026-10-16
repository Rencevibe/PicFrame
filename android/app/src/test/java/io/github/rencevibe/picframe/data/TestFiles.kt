package io.github.rencevibe.picframe.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.random.Random

/** java.io.File stand-in for the picked SAF folder. Records every open. */
class FileTree(private val root: File) : DocumentTree {
    val opens = mutableListOf<String>()

    override val displayName: String get() = root.name

    override fun listFiles(): List<DocEntry> = root.walkTopDown()
        .onEnter { it == root || !it.name.startsWith(".") }
        .filter { it.isFile }
        .map { entry(it) }
        .toList()

    override fun child(name: String): DocEntry? = File(root, name).takeIf { it.isFile }?.let { entry(it) }

    override fun open(id: String): InputStream? {
        opens += id
        return File(id).takeIf { it.isFile }?.inputStream()
    }

    fun entry(f: File) = DocEntry(f.path, f.relativeTo(root).path.replace(File.separatorChar, '/'), f.lastModified(), f.length())
}

/** A solid-color image, optionally with a band painted on it and an EXIF orientation. */
fun makeImage(
    file: File, w: Int = 800, h: Int = 600, color: Int = 0xFFC8783C.toInt(),
    format: Bitmap.CompressFormat = if (file.name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
    band: Pair<android.graphics.Rect, Int>? = null, exifOrientation: Int? = null,
): File {
    file.parentFile?.mkdirs()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    b.eraseColor(color)
    band?.let { (r, c) -> Canvas(b).drawRect(r, Paint().apply { this.color = c }) }
    FileOutputStream(file).use { b.compress(format, 95, it) }
    exifOrientation?.let {
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, it.toString())
            saveAttributes()
        }
    }
    return file
}

/** Random pixels: compresses badly, so cache files are big and similar in size. */
fun noise(file: File, w: Int, h: Int): File {
    val rnd = Random(file.name.hashCode())
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    b.setPixels(IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0x1000000) }, 0, w, 0, 0, w, h)
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
