package io.github.rencevibe.picframe.data

import io.github.rencevibe.picframe.domain.PhotoQueue
import io.github.rencevibe.picframe.domain.isPhotoPath
import kotlin.random.Random

/** Scans the picked folder and hands out photos in shuffle order (PhotoQueue rules).
 *  Thread-safe: scans run on a worker while the slideshow takes photos. */
class PhotoSource(
    private val tree: () -> DocumentTree?,
    rng: Random = Random.Default,
    private val webp: Boolean = true, // Android decodes WebP on every supported version
    private val log: (String) -> Unit = {},
) {
    private val queue = PhotoQueue(rng)
    private var entries: Map<String, DocEntry> = emptyMap()

    val count: Int @Synchronized get() = entries.size

    /** Re-reads the folder; returns the number of photos. An unreadable folder counts as empty. */
    @Synchronized
    fun scan(): Int {
        val found = try {
            tree()?.listFiles()?.filter { isPhotoPath(it.relPath, webp) }.orEmpty()
        } catch (e: Exception) { // access revoked, provider gone
            log("photos: folder unreadable ($e)")
            emptyList()
        }
        entries = found.associateBy { it.id }
        return queue.update(entries.keys)
    }

    @Synchronized
    fun next(): DocEntry? = queue.next()?.let { entries[it] }

    @Synchronized
    fun entry(id: String): DocEntry? = entries[id]
}
