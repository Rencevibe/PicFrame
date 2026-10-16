package io.github.rencevibe.picframe.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.InputStream

/** A file in the picked folder: [id] opens it, [relPath] is "sub/a.jpg". */
data class DocEntry(val id: String, val relPath: String, val lastModifiedMs: Long, val size: Long)

/** The photo folder the user picked (D3). Implemented over the Storage Access Framework; tests use
 *  a java.io.File implementation. Calls may throw if access was revoked. */
interface DocumentTree {
    val displayName: String

    /** Every file below the folder; folders whose name starts with "." are not entered. */
    fun listFiles(): List<DocEntry>

    /** A file directly in the folder, by name. */
    fun child(name: String): DocEntry?

    fun open(id: String): InputStream?
}

class SafTree(private val context: Context, private val treeUri: Uri) : DocumentTree {
    private val resolver get() = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(treeUri)

    override val displayName: String
        get() = resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
            arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: treeUri.lastPathSegment.orEmpty()

    private class Row(val id: String, val name: String, val isDir: Boolean, val modified: Long, val size: Long)

    private fun children(docId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Row>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Row(
                    id = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    modified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    private fun entry(row: Row, rel: String) = DocEntry(
        DocumentsContract.buildDocumentUriUsingTree(treeUri, row.id).toString(), rel, row.modified, row.size,
    )

    override fun listFiles(): List<DocEntry> {
        val out = mutableListOf<DocEntry>()
        val pending = ArrayDeque(listOf(rootId to ""))
        while (pending.isNotEmpty()) {
            val (docId, prefix) = pending.removeFirst()
            for (row in children(docId)) {
                val rel = prefix + row.name
                if (row.isDir) {
                    if (!row.name.startsWith(".")) pending.addLast(row.id to "$rel/")
                } else {
                    out += entry(row, rel)
                }
            }
        }
        return out
    }

    override fun child(name: String): DocEntry? =
        children(rootId).firstOrNull { !it.isDir && it.name == name }?.let { entry(it, name) }

    override fun open(id: String): InputStream? = resolver.openInputStream(Uri.parse(id))
}
