package com.wangzan101.mika.memory

import android.security.keystore.UserNotAuthenticatedException
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryPersistence
import com.wangzan101.mika.core.workspace.AtomicFiles
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * 整份记忆加密成 `memory/memory.bin`(AES-GCM,密钥由调用方给,正式用 `KeystoreKeyCipher("mika-memory")`)。
 * 写临时文件后原子改名,文件要么是完整密文要么不变。
 * 解不开(密钥丢了)或内容坏了:不覆盖,改名另存,从空开始,[brokenNotice] 给界面提示用。
 */
class EncryptedMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : MemoryPersistence {
    private val memDir = File(dir, "memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryItem.serializer())

    private val _brokenNotice = MutableStateFlow<String?>(null)
    /** 另存的坏文件名;null = 没出过问题。 */
    val brokenNotice: StateFlow<String?> = _brokenNotice.asStateFlow()

    @Synchronized
    override fun load(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes() // 读不了是 IO 故障,照常抛出,不当作坏文件处理
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // 密钥库一时不可用(没解锁、繁忙)不等于文件坏了:抛出,下次再读,绝不另存
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            // 密钥失效、认证失败、截断、JSON 坏:都另存,别让下一次写入盖掉
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<MemoryItem>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        // 先落到磁盘再改名:断电时不会留下改了名却是空的文件
        AtomicFiles.write(file, bytes, durable = true, temp = tmp)
    }

    /** 「全部清除」:删记忆文件(含另存的坏文件)。密钥重建由调用方删别名后做。 */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
        _brokenNotice.value = null
    }

    private fun moveAside() {
        _brokenNotice.value = AtomicFiles.quarantine(file, AtomicFiles.Quarantine.MoveBroken, now = clock())!!.name
    }

    private companion object {
        const val FILE = "memory.bin"
    }
}
