package com.wangzan101.mika.task

import java.io.File

/** 账本读不出来时 `AtomicFiles.quarantine(CopyCorrupt)` 留下的现场文件,新的在后。 */
internal fun File.corruptCopies(): List<File> =
    parentFile?.listFiles { f -> f.name.startsWith("$name.corrupt-") }.orEmpty().sortedBy { it.name }
