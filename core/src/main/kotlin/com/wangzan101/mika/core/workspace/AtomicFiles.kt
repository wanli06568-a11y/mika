package com.wangzan101.mika.core.workspace

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 小文件的「先写临时文件、再换到目标」和读不出来时的「坏文件留现场」。
 *
 * 各调用点原来的落盘策略并不相同，这里只把写法收成一处，**策略由调用点照原样传参**：
 * - [durable]：改名前 fsync（断电后不会留下改了名却是空的文件）。不传就不落盘。
 * - [Move]：临时文件怎么换到目标上。`renameTo` 失败只返回 false，`Files.move` 失败抛异常。
 * - [Fallback]：换不过去时怎么办（报给调用方 / 直接覆盖写目标 / 删掉目标再改名一次）。
 *
 * 不建父目录：要建的调用点自己建（原来没建的，父目录不在就照旧失败）。
 */
object AtomicFiles {
    enum class Move {
        /** `File.renameTo`；失败返回 false。 */
        Rename,
        /** `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；失败抛异常。 */
        Atomic,
        /** 先 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`，抛异常就再试一次 `renameTo`。 */
        AtomicThenRename,
    }

    enum class Fallback {
        /** 不补救：返回 false，临时文件留着由调用方处理。 */
        None,
        /** 直接覆盖写目标（退回非原子写），再删临时文件。 */
        WriteDirect,
        /** 删掉目标再改名一次；这次的结果就是返回值。 */
        DeleteAndRetry,
    }

    /** 默认的临时文件：目标旁边的 `<名字>.tmp`。 */
    fun tempFor(target: File): File = File(target.parentFile, target.name + ".tmp")

    /**
     * 把 [bytes] 写到 [temp]，再按 [move] 换到 [target]；换不过去时按 [fallback] 处理。
     * 返回内容是否已经在 [target] 上。写临时文件出错、[Move.Atomic] 换不过去时照常抛出。
     */
    fun write(
        target: File,
        bytes: ByteArray,
        durable: Boolean,
        move: Move = Move.Atomic,
        fallback: Fallback = Fallback.None,
        temp: File = tempFor(target),
    ): Boolean {
        if (durable) FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
        else temp.writeBytes(bytes)
        if (replace(temp, target, move)) return true
        return when (fallback) {
            Fallback.None -> false
            Fallback.WriteDirect -> { target.writeBytes(bytes); temp.delete(); true }
            Fallback.DeleteAndRetry -> { target.delete(); temp.renameTo(target) }
        }
    }

    /** 文本按 UTF-8 写，其余同 [write]。 */
    fun write(
        target: File,
        text: String,
        durable: Boolean,
        move: Move = Move.Atomic,
        fallback: Fallback = Fallback.None,
        temp: File = tempFor(target),
    ): Boolean = write(target, text.toByteArray(Charsets.UTF_8), durable, move, fallback, temp)

    private fun replace(temp: File, target: File, move: Move): Boolean = when (move) {
        Move.Rename -> temp.renameTo(target)
        Move.Atomic -> {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }
        Move.AtomicThenRename -> try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            temp.renameTo(target)
        }
    }

    /** 坏文件怎么留现场。名字都是 `<原名>.<标签>-<时间>`，[dedupe] 时同名已存在就加 `-1`、`-2`…… */
    enum class Quarantine(internal val tag: String) {
        /** 复制一份 `.corrupt-<毫秒>`，原文件留在原处（随后照常被重写）。复制失败抛异常。 */
        CopyCorrupt("corrupt"),
        /** 改名成 `.corrupt-<毫秒>`。改名失败返回 null。 */
        RenameCorrupt("corrupt"),
        /** 改名成 `.bad-<毫秒>`。改名失败返回 null。 */
        RenameBad("bad"),
        /** `Files.move(ATOMIC_MOVE)` 挪成 `.broken-<yyyyMMddHHmmss>`（本地时区）。挪不动抛异常。 */
        MoveBroken("broken"),
    }

    /**
     * 按 [style] 把 [file] 留成现场，返回留下的那份；[Quarantine.RenameCorrupt]、[Quarantine.RenameBad] 改名失败时返回 null。
     * [dedupe] 为 false 时不查重名（同名已存在时由 `renameTo` 照系统行为处理），用于原来就不查的调用点。
     */
    fun quarantine(file: File, style: Quarantine, now: Long = System.currentTimeMillis(), dedupe: Boolean = true): File? {
        val stamp = if (style == Quarantine.MoveBroken) SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(now) else now.toString()
        val base = "${file.name}.${style.tag}-$stamp"
        var backup = File(file.parentFile, base)
        var n = 1
        if (dedupe) while (backup.exists()) backup = File(file.parentFile, "$base-${n++}")
        return when (style) {
            Quarantine.CopyCorrupt -> { file.copyTo(backup); backup }
            Quarantine.RenameCorrupt, Quarantine.RenameBad -> backup.takeIf { file.renameTo(it) }
            Quarantine.MoveBroken -> { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE); backup }
        }
    }
}
