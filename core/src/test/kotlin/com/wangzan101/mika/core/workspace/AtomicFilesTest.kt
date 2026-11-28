package com.wangzan101.mika.core.workspace

import com.wangzan101.mika.core.workspace.AtomicFiles.Fallback
import com.wangzan101.mika.core.workspace.AtomicFiles.Move
import com.wangzan101.mika.core.workspace.AtomicFiles.Quarantine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale

class AtomicFilesTest {
    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("atomic-files").toFile() }
    @After fun tearDown() {
        root.walkTopDown().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    // ---- 写成功：每种换法、落不落盘都把内容放到目标上，临时文件不留 ----

    @Test fun everyMoveReplacesExistingTarget() {
        for (move in Move.entries) for (durable in listOf(false, true)) {
            val target = File(root, "$move-$durable.json").apply { writeText("old") }
            assertTrue(AtomicFiles.write(target, "new-$move", durable, move))
            assertEquals("new-$move", target.readText())
            assertFalse(AtomicFiles.tempFor(target).exists())
        }
    }

    @Test fun createsMissingTargetAndWritesBytesAsIs() {
        val target = File(root, "a.bin")
        val bytes = byteArrayOf(0, 1, -1, 42)
        assertTrue(AtomicFiles.write(target, bytes, durable = true, move = Move.Rename))
        assertTrue(bytes.contentEquals(target.readBytes()))
    }

    @Test fun textIsUtf8() {
        val target = File(root, "t.txt")
        AtomicFiles.write(target, "中文 ✓", durable = false)
        assertEquals("中文 ✓", target.readText(Charsets.UTF_8))
    }

    @Test fun defaultTempSitsNextToTarget() {
        assertEquals(File(root, "x.json.tmp"), AtomicFiles.tempFor(File(root, "x.json")))
    }

    @Test fun customTempIsUsedAndConsumed() {
        val target = File(root, "c.json")
        val temp = File(root, "elsewhere.tmp")
        assertTrue(AtomicFiles.write(target, "v", durable = false, move = Move.Rename, temp = temp))
        assertEquals("v", target.readText())
        assertFalse(temp.exists())
        assertFalse(AtomicFiles.tempFor(target).exists())
    }

    @Test fun missingParentIsNotCreated() {
        val target = File(root, "no/such/dir/f.json")
        assertThrows(IOException::class.java) { AtomicFiles.write(target, "v", durable = true) }
        assertThrows(IOException::class.java) { AtomicFiles.write(target, "v", durable = false) }
        assertFalse(target.parentFile.exists())
    }

    // ---- 换不过去：目标是非空目录，改名和覆盖写都不行 ----

    @Test fun renameFailureWithoutFallbackReportsFalseAndKeepsTemp() {
        val target = nonEmptyDir("r")
        assertFalse(AtomicFiles.write(target, "v", durable = false, move = Move.Rename))
        assertEquals("v", AtomicFiles.tempFor(target).readText())
    }

    @Test fun atomicFailureThrowsAndKeepsTemp() {
        val target = nonEmptyDir("a")
        assertThrows(IOException::class.java) { AtomicFiles.write(target, "v", durable = true, move = Move.Atomic) }
        assertEquals("v", AtomicFiles.tempFor(target).readText())
    }

    @Test fun atomicThenRenameReportsFalseWhenBothFail() {
        val target = nonEmptyDir("ar")
        assertFalse(AtomicFiles.write(target, "v", durable = false, move = Move.AtomicThenRename))
        assertTrue(AtomicFiles.tempFor(target).exists())
    }

    @Test fun deleteAndRetryReportsFalseWhenTargetCannotBeDeleted() {
        val target = nonEmptyDir("d")
        assertFalse(AtomicFiles.write(target, "v", durable = false, move = Move.Rename, fallback = Fallback.DeleteAndRetry))
        assertTrue(target.isDirectory)
    }

    @Test fun writeDirectThrowsWhenTargetCannotBeWritten() {
        val target = nonEmptyDir("w")
        assertThrows(IOException::class.java) {
            AtomicFiles.write(target, "v", durable = false, move = Move.Rename, fallback = Fallback.WriteDirect)
        }
    }

    // ---- 补救成功 ----

    @Test fun deleteAndRetrySucceedsOnceTargetIsGone() {
        // 文件改名到空目录上会失败;删掉空目录后再改名就成了
        val target = File(root, "e").apply { mkdir() }
        assertTrue(AtomicFiles.write(target, "v", durable = false, move = Move.Rename, fallback = Fallback.DeleteAndRetry))
        assertEquals("v", target.readText())
        assertFalse(AtomicFiles.tempFor(target).exists())
    }

    @Test fun writeDirectOverwritesTargetWhenRenameIsRefused() {
        // 目录只读:改名进不去,但目标文件本身可写
        for (move in listOf(Move.Rename, Move.AtomicThenRename)) {
            val (target, temp) = readOnlyDirTarget("ro-$move")
            assertTrue(AtomicFiles.write(target, "new", durable = true, move = move, fallback = Fallback.WriteDirect, temp = temp))
            assertEquals("new", target.readText())
            assertFalse(temp.exists())
        }
    }

    @Test fun refusedRenameWithoutFallbackLeavesTargetAlone() {
        val (target, temp) = readOnlyDirTarget("ro-none")
        assertFalse(AtomicFiles.write(target, "new", durable = false, move = Move.Rename, temp = temp))
        assertEquals("old", target.readText())
        assertTrue(temp.exists())
        val (target2, temp2) = readOnlyDirTarget("ro-atomic")
        assertThrows(IOException::class.java) { AtomicFiles.write(target2, "new", durable = false, temp = temp2) }
        assertEquals("old", target2.readText())
    }

    // ---- 坏文件留现场 ----

    @Test fun copyCorruptKeepsOriginalAndDedupes() {
        val f = File(root, "runs.json").apply { writeText("first") }
        val a = AtomicFiles.quarantine(f, Quarantine.CopyCorrupt, now = 5)!!
        f.writeText("second")
        val b = AtomicFiles.quarantine(f, Quarantine.CopyCorrupt, now = 5)!!
        assertEquals("runs.json.corrupt-5", a.name)
        assertEquals("runs.json.corrupt-5-1", b.name)
        assertEquals(listOf("first", "second"), listOf(a.readText(), b.readText()))
        assertEquals("second", f.readText())
    }

    @Test fun copyCorruptThrowsWhenSourceIsMissing() {
        assertThrows(Exception::class.java) { AtomicFiles.quarantine(File(root, "gone"), Quarantine.CopyCorrupt, now = 1) }
    }

    @Test fun renameStylesMoveFileAway() {
        val c = File(root, "q.json").apply { writeText("x") }
        val moved = AtomicFiles.quarantine(c, Quarantine.RenameCorrupt, now = 7)!!
        assertEquals("q.json.corrupt-7", moved.name)
        assertFalse(c.exists())
        assertEquals("x", moved.readText())

        val b = File(root, "p.json").apply { writeText("y") }
        assertEquals("p.json.bad-9", AtomicFiles.quarantine(b, Quarantine.RenameBad, now = 9)!!.name)
        assertFalse(b.exists())
    }

    @Test fun renameDedupesUnlessToldNot() {
        val f = File(root, "d.json")
        File(root, "d.json.corrupt-3").writeText("earlier")
        f.writeText("later")
        assertEquals("d.json.corrupt-3-1", AtomicFiles.quarantine(f, Quarantine.RenameCorrupt, now = 3)!!.name)
        assertEquals("earlier", File(root, "d.json.corrupt-3").readText())

        f.writeText("latest")
        val kept = AtomicFiles.quarantine(f, Quarantine.RenameCorrupt, now = 3, dedupe = false)!!
        assertEquals("d.json.corrupt-3", kept.name)
        assertEquals("latest", kept.readText())
    }

    @Test fun renameFailureReturnsNull() {
        assertNull(AtomicFiles.quarantine(File(root, "none.json"), Quarantine.RenameCorrupt, now = 1))
        assertNull(AtomicFiles.quarantine(File(root, "none.json"), Quarantine.RenameBad, now = 1))
    }

    @Test fun moveBrokenUsesSecondStampAndDedupes() {
        val now = 1_700_000_000_000L
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now)
        val f = File(root, "m.bin").apply { writeText("1") }
        assertEquals("m.bin.broken-$stamp", AtomicFiles.quarantine(f, Quarantine.MoveBroken, now)!!.name)
        f.writeText("2")
        assertEquals("m.bin.broken-$stamp-1", AtomicFiles.quarantine(f, Quarantine.MoveBroken, now)!!.name)
        assertFalse(f.exists())
    }

    @Test fun moveBrokenThrowsWhenSourceIsMissing() {
        assertThrows(IOException::class.java) { AtomicFiles.quarantine(File(root, "gone.bin"), Quarantine.MoveBroken, now = 1) }
    }

    private fun nonEmptyDir(name: String): File =
        File(root, name).apply { mkdir(); File(this, "inside").writeText("keep") }

    /** 目标文件可写、所在目录只读;临时文件放在别的可写目录里。 */
    private fun readOnlyDirTarget(name: String): Pair<File, File> {
        val dir = File(root, name).apply { mkdir() }
        val target = File(dir, "f.json").apply { writeText("old") }
        val temp = File(File(root, "$name-tmp").apply { mkdir() }, "f.json.tmp")
        assumeTrue(dir.setWritable(false, false))
        // 以 root 跑时权限不生效,这几条测不了
        assumeTrue(!File(dir, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })
        return target to temp
    }
}
