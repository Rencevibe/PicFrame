package io.github.rencevibe.picframe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/** Folder scanning (test_photos.py library cases, against files) and todo.toml loading. */
@RunWith(RobolectricTestRunner::class)
class SourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun scanCountsSupportedOnlyAndSkipsHiddenFolders() {
        val root = tmp.newFolder("photos")
        for (name in listOf("a.jpg", "b.JPEG", "c.png", "sub/d.jpg", "e.webp", ".hidden.jpg", ".git/x.jpg", "notes.txt")) {
            File(root, name).apply { parentFile?.mkdirs(); writeText("x") }
        }
        val tree = FileTree(root)
        assertEquals(4, PhotoSource({ tree }, webp = false).scan())
        assertEquals(5, PhotoSource({ tree }).scan())
    }

    @Test fun newFileFoundOnRescanAndNoFolderIsEmpty() {
        val root = tmp.newFolder("photos")
        File(root, "a.jpg").writeText("x")
        val tree = FileTree(root)
        val src = PhotoSource({ tree }, Random(2))
        assertEquals(1, src.scan())
        assertEquals("a.jpg", src.next()!!.relPath)
        File(root, "b.jpg").writeText("x")
        File(root, "a.jpg").delete()
        assertEquals(1, src.scan())
        assertEquals(listOf("b.jpg", "b.jpg"), (0 until 2).map { src.next()!!.relPath })
        val none = PhotoSource({ null })
        assertEquals(0, none.scan())
        assertNull(none.next())
    }

    @Test fun unreadableFolderCountsAsEmpty() {
        val broken = object : DocumentTree {
            override val displayName = "x"
            override fun listFiles(): List<DocEntry> = throw SecurityException("permission revoked")
            override fun child(name: String): DocEntry? = null
            override fun open(id: String) = null
        }
        val logged = mutableListOf<String>()
        assertEquals(0, PhotoSource({ broken }, log = { logged += it }).scan())
        assertEquals(1, logged.size)
    }

    @Test fun todoLoadsFromTheFolder() {
        val root = tmp.newFolder("photos")
        val tree = FileTree(root)
        val todo = TodoSource({ tree })
        assertEquals(0, todo.load().size) // no file yet
        File(root, "todo.toml").writeText("[[item]]\ntitle = \"Dentist\"\nkind = \"appointment\"\ntime = \"13:30\"\n")
        assertEquals(listOf("Dentist"), todo.load().map { it.title })
        assertEquals(0, TodoSource({ null }).load().size)
    }
}
