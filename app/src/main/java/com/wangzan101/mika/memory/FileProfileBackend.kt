package com.wangzan101.mika.memory

import com.wangzan101.mika.core.learn.memory.ProfileBackend
import com.wangzan101.mika.core.learn.memory.ProfileEntry
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 与普通记忆分开的用户画像文件；全量原子替换，保留被替代条目。 */
class FileProfileBackend(private val file: File, private val cipher: KeyCipher) : ProfileBackend {
    private val codec = ListSerializer(ProfileEntry.serializer())

    @Synchronized override fun read(): List<ProfileEntry> =
        if (!file.exists()) emptyList() else Json.decodeFromString(codec, cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8))

    @Synchronized override fun write(entries: List<ProfileEntry>) {
        file.parentFile.mkdirs()
        AtomicFiles.write(file, cipher.encrypt(Json.encodeToString(codec, entries).toByteArray(Charsets.UTF_8)), durable = false)
    }
}
