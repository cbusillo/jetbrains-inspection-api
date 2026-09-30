package com.shiny.inspectionmcp

import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.xmlb.XmlSerializer
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HelperPythonSdkLifecycleTest {
    @TempDir lateinit var directory: Path

    private fun home(root: Path) = root.resolve(if (SystemInfo.isWindows) ".venv/Scripts/python.exe" else ".venv/bin/python").toString()
    private fun sdk(root: Path, name: String = helperSdkName()): Sdk = mockk<Sdk>().also {
        every { it.name } returns name
        every { it.homePath } returns home(root)
        every { it.sdkType } returns mockk<SdkType>().also { type -> every { type.name } returns "Python SDK" }
    }

    private class Fixture(val registry: HelperSdkOwnershipRegistry = HelperSdkOwnershipRegistry()) {
        val registered = mutableListOf<Sdk>()
        val table = mockk<ProjectJdkTable>()
        var saved = false
        var busy = false
        init {
            every { table.allJdks } answers { registered.toTypedArray() }
            every { table.removeJdk(any()) } answers { registered.remove(firstArg<Sdk>()); Unit }
        }
        fun lifecycle() = HelperPythonSdkLifecycle(registry, { table }, { _, _ -> busy }, { it() }, { it() }, { saved = true })
    }

    @Test fun `dry run lists and apply removes only a recorded SDK and persists`() {
        val root = directory.resolve("worktree")
        val owned = sdk(root)
        val manual = sdk(directory.resolve("manual"), "user SDK")
        val fixture = Fixture()
        fixture.registered.addAll(listOf(owned, manual))
        fixture.registry.recordAddedSdk(owned, root)
        val restored = HelperSdkOwnershipRegistry().apply {
            loadState(XmlSerializer.deserialize(XmlSerializer.serialize(fixture.registry.state), HelperSdkOwnershipState::class.java))
        }
        val lifecycle = HelperPythonSdkLifecycle(restored, { fixture.table }, { _, _ -> false }, { it() }, { it() }, { fixture.saved = true })

        val preview = lifecycle.unregister(root, true)
        assertEquals("would_remove", entries(preview).single()["status"])
        assertEquals(listOf(owned, manual), fixture.registered)
        assertFalse(fixture.saved)
        assertEquals(1, restored.state.records.size)

        val applied = lifecycle.unregister(root, false)
        assertEquals("removed", entries(applied).single()["status"])
        assertEquals(listOf(manual), fixture.registered)
        assertEquals(0, restored.state.records.size)
        assertTrue(fixture.saved)
    }

    @Test fun `refuses unrecorded SDK even with helper looking name and matching interpreter`() {
        val root = directory.resolve("worktree")
        val unowned = sdk(root, "Inspection .venv")
        val fixture = Fixture().apply { registered += unowned }
        val result = fixture.lifecycle().unregister(root, false)
        assertEquals("refused", result["status"])
        assertEquals("not_helper_owned", entries(result).single()["reason"])
        assertEquals(listOf(unowned), fixture.registered)
    }

    @Test fun `refuses a changed interpreter instead of trusting a stored name`() {
        val root = directory.resolve("worktree")
        val original = sdk(root)
        val replacement = sdk(directory.resolve("other"), original.name)
        val fixture = Fixture().apply { registry.recordAddedSdk(original, root); registered += replacement }
        val result = fixture.lifecycle().unregister(root, false)
        assertEquals("ownership_mismatch", entries(result).single()["reason"])
        assertEquals(listOf(replacement), fixture.registered)
        assertEquals(1, fixture.registry.state.records.size)
    }

    @Test fun `equivalent interpreter spelling still identifies the owned SDK`() {
        val root = directory.resolve("worktree")
        val owned = sdk(root)
        every { owned.homePath } returns root.resolve(if (SystemInfo.isWindows) ".venv/Scripts/./python.exe" else ".venv/bin/./python").toString()
        val fixture = Fixture().apply { registry.recordAddedSdk(owned, root); registered += owned }
        assertEquals("removed", entries(fixture.lifecycle().unregister(root, false)).single()["status"])
        assertTrue(fixture.registered.isEmpty())
    }

    @Test fun `refuses SDK still used by an open project`() {
        val root = directory.resolve("worktree")
        val owned = sdk(root)
        val fixture = Fixture().apply { registry.recordAddedSdk(owned, root); registered += owned; busy = true }
        assertEquals("sdk_in_use", entries(fixture.lifecycle().unregister(root, false)).single()["reason"])
        assertEquals(listOf(owned), fixture.registered)
        assertEquals(1, fixture.registry.state.records.size)
    }

    @Test fun `orphan cleanup leaves retained worktrees and unrecorded SDKs alone`() {
        val missing = directory.resolve("removed")
        val retained = Files.createDirectory(directory.resolve("retained"))
        val orphan = sdk(missing)
        val live = sdk(retained)
        val manual = sdk(directory.resolve("also-removed"), "Inspection .venv (42)")
        val fixture = Fixture().apply {
            registered.addAll(listOf(orphan, live, manual))
            registry.recordAddedSdk(orphan, missing)
            registry.recordAddedSdk(live, retained)
        }
        val result = fixture.lifecycle().unregister(null, false)
        assertEquals("removed", entries(result).single()["status"])
        assertEquals(listOf(live, manual), fixture.registered)
        assertEquals(listOf(live.name), fixture.registry.state.records.map { it.sdkName })
    }

    @Test fun `renamed owned SDK retains ownership and refuses orphan cleanup`() {
        val root = directory.resolve("removed")
        val owned = sdk(root)
        val fixture = Fixture().apply { registry.recordAddedSdk(owned, root); registered += owned }
        every { owned.name } returns "renamed"
        val result = fixture.lifecycle().unregister(null, false)
        assertEquals("ownership_mismatch", entries(result).single()["reason"])
        assertEquals(listOf(owned), fixture.registered)
        assertEquals(1, fixture.registry.state.records.size)
    }

    @Test fun `worktree symlink selector identifies the recorded SDK`() {
        val root = Files.createDirectory(directory.resolve("worktree"))
        val alias = Files.createSymbolicLink(directory.resolve("alias"), root)
        val owned = sdk(root)
        val fixture = Fixture().apply { registry.recordAddedSdk(owned, root); registered += owned }
        assertEquals("removed", entries(fixture.lifecycle().unregister(alias, false)).single()["status"])
        assertTrue(fixture.registered.isEmpty())
    }

    @Test fun `missing parent never establishes an orphan`() {
        val root = directory.resolve("unmounted").resolve("removed")
        val owned = sdk(root)
        val fixture = Fixture().apply { registry.recordAddedSdk(owned, root); registered += owned }
        assertTrue(entries(fixture.lifecycle().unregister(null, false)).isEmpty())
        assertEquals(listOf(owned), fixture.registered)
    }

    @Test fun `unrelated shallow SDK homes do not crash owned retirement`() {
        val root = directory.resolve("worktree")
        val owned = sdk(root)
        val system = sdk(root, "system")
        every { system.homePath } returns "/python"
        val fixture = Fixture().apply { registered.addAll(listOf(owned, system)); registry.recordAddedSdk(owned, root) }
        assertEquals("would_remove", entries(fixture.lifecycle().unregister(root, true)).single()["status"])
        assertEquals("removed", entries(fixture.lifecycle().unregister(root, false)).single()["status"])
        assertEquals(listOf(system), fixture.registered)
    }

    @Test fun `modal scheduling timeout is a refusal and does not save settings`() {
        val root = directory.resolve("worktree")
        val owned = sdk(root)
        val fixture = Fixture().apply { registered += owned; registry.recordAddedSdk(owned, root) }
        val lifecycle = HelperPythonSdkLifecycle(fixture.registry, { fixture.table }, { _, _ -> false },
            { throw java.util.concurrent.TimeoutException() }, { it() }, { fixture.saved = true })
        val result = lifecycle.unregister(root, false)
        assertEquals("refused", result["status"])
        assertEquals("sdk_lifecycle_busy", result["reason"])
        assertEquals(listOf(owned), fixture.registered)
        assertFalse(fixture.saved)
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(result: Map<String, Any?>) = result["sdks"] as List<Map<String, Any?>>
}
