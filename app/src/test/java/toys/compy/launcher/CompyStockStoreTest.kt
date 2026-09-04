/*
 * Copyright (c) 2025 Danila Sentyabov (dsent.me)
 * Licensed under the MIT License.
 */

package toys.compy.launcher

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stock parser and destructive replacement transaction are critical recovery paths. */
class CompyStockStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun catalogReadsIndexedVersionsDefaultsAndIgnoresIncomingTrees() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeVersion("alpha", "v1", "one")
        fixture.writeVersion("alpha", "v2", "two")
        fixture.writeIndexes("alpha", listOf("v1", "v2"), "v2")
        File(fixture.programs, ".incoming.partial").mkdirs()
        File(fixture.programs, "alpha/v1/.incoming.partial").mkdirs()
        File(fixture.programs, "alpha/v1/.incoming.partial/ignored.txt").writeText("partial")

        val catalog = fixture.store().readCatalog()

        assertEquals(listOf("alpha"), catalog.programs.map { it.name })
        val program = catalog.programs.single()
        assertEquals("v2", program.defaultToken)
        assertEquals(listOf("v1", "v2"), program.versions.map { it.token })
        assertEquals(
            listOf("projects/alpha/main.lua"),
            program.versions.first().files.map { it.path },
        )
        assertNotEquals(
            program.versions[0].sourceManifestSha256,
            program.versions[1].sourceManifestSha256,
        )
    }

    @Test
    fun catalogRejectsAnIncompleteIndexedVersionBeforeRestore() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeVersion("alpha", "v1", "one")
        fixture.writeIndexes("alpha", listOf("v1", "missing"), "v1")

        expectThrows<IOException> { fixture.store().readCatalog() }

        assertFalse(File(fixture.projects, "alpha").exists())
    }

    @Test
    fun restoreRejectsAChangedStockSelectionBeforeReplacingAnyProject() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeVersion("alpha", "v1", "stock alpha")
        fixture.writeIndexes("alpha", listOf("v1"), "v1")
        fixture.writeVersion("beta", "b1", "stock beta")
        fixture.writeIndexes("beta", listOf("b1"), "b1")
        fixture.writeProject("alpha", "changed alpha")
        fixture.writeProject("beta", "changed beta")
        val store = fixture.store()
        val catalog = store.readCatalog()
        File(fixture.programs, "beta/b1/main.lua").writeText("modified after selection")

        expectThrows<IOException> {
            store.restoreAll(catalog, mapOf("alpha" to "v1", "beta" to "b1"))
        }

        assertEquals("changed alpha", File(fixture.projects, "alpha/main.lua").readText())
        assertEquals("changed beta", File(fixture.projects, "beta/main.lua").readText())
        assertFalse(File(fixture.projects, "alpha.old").exists())
    }

    @Test
    fun restoreUsesSelectedVersionsPreservesTargetsAndIsIdempotent() {
        val fixture = Fixture(temporaryFolder.newFolder())
        fixture.writeVersion("alpha", "v1", "stock one")
        fixture.writeVersion("alpha", "v2", "stock two")
        fixture.writeIndexes("alpha", listOf("v1", "v2"), "v2")
        fixture.writeVersion("beta", "b1", "stock beta")
        fixture.writeIndexes("beta", listOf("b1"), "b1")
        fixture.writeProject("alpha", "changed")
        fixture.writeProject("alpha.old", "oldest")
        fixture.writeProject("alpha.old.2", "newer")

        val store = fixture.store()
        val catalog = store.readCatalog()
        val first = store.restoreAll(catalog, mapOf("alpha" to "v1", "beta" to "b1"))

        assertEquals(StockRestoreResult(2, 1, 0), first)
        assertEquals("stock one", File(fixture.projects, "alpha/main.lua").readText())
        assertEquals("changed", File(fixture.projects, "alpha.old.3/main.lua").readText())
        assertEquals("stock beta", File(fixture.projects, "beta/main.lua").readText())
        assertFalse(File(fixture.projects, "alpha.old.1").exists())

        val second = store.restoreAll(catalog, mapOf("alpha" to "v1", "beta" to "b1"))
        assertEquals(StockRestoreResult(0, 0, 2), second)
        assertFalse(File(fixture.projects, "alpha.old.4").exists())
        assertTrue(
            fixture.projects.listFiles().orEmpty().none {
                it.name.startsWith(".incoming.") || it.name.startsWith(".restore.")
            },
        )
    }

    @Test
    fun restoreCanUseInternalStockForTheActiveCardProjects() {
        val source = Fixture(temporaryFolder.newFolder("internal"), BackupSourceKind.INTERNAL)
        val target = Fixture(temporaryFolder.newFolder("card"), BackupSourceKind.CARD)
        source.writeVersion("alpha", "v1", "internal stock")
        source.writeIndexes("alpha", listOf("v1"), "v1")
        target.writeProject("alpha", "card project")
        val store = CompyStockStore(source.endpoint, target.endpoint)
        val catalog = store.readCatalog()

        val result = store.restoreAll(catalog, mapOf("alpha" to "v1"))

        assertEquals(StockRestoreResult(1, 1, 0), result)
        assertEquals("internal stock", File(target.projects, "alpha/main.lua").readText())
        assertEquals("card project", File(target.projects, "alpha.old/main.lua").readText())
        assertFalse(File(source.projects, "alpha").exists())
    }

    private class Fixture(
        root: File,
        kind: BackupSourceKind = BackupSourceKind.CARD,
    ) {
        val compy = File(root, "Documents/compy")
        val projects = File(compy, "projects")
        val programs = File(compy, "stock/programs")
        val endpoint =
            if (kind == BackupSourceKind.CARD) {
                BackupStorageEndpoint(kind, "fs:7aff-7538", compy)
            } else {
                BackupStorageEndpoint(kind, "00000000-0000-0000-0000-000000000001", compy)
            }

        init {
            projects.mkdirs()
            programs.mkdirs()
        }

        fun store(): CompyStockStore = CompyStockStore(endpoint)

        fun writeVersion(name: String, token: String, contents: String) {
            val directory = File(programs, "$name/$token")
            directory.mkdirs()
            File(directory, "main.lua").writeText(contents)
        }

        fun writeIndexes(name: String, versions: List<String>, default: String) {
            val directory = File(programs, name)
            directory.mkdirs()
            File(directory, "versions.txt").writeText(versions.joinToString("\n", postfix = "\n"))
            File(directory, "stock.txt").writeText("$default\n")
        }

        fun writeProject(name: String, contents: String) {
            val directory = File(projects, name)
            directory.mkdirs()
            File(directory, "main.lua").writeText(contents)
        }
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
