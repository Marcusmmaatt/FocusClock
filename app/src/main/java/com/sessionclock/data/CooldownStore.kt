package com.sessionclock.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * # 冷静期绝对时间戳存储（EC-03 / F-09 / F-11 的防作弊基石）
 *
 * 为什么不用 DataStore：
 * 强拦截触发后仅 1.5s 就要执行 Home 强退，用户还可能立刻重启手机。
 * DataStore 的写盘是异步的，无法保证在强退/断电前 fsync 完成。
 * 本类对内部存储文件执行**同步写入 + FileDescriptor.sync() 强制落盘**，
 * 写完才返回，状态机随后才允许执行强退动作。
 *
 * 文件内容仅一行：冷静期截止的 Epoch 毫秒（System.currentTimeMillis() + 300_000）。
 * 文件不存在 / 内容非法 / 已过期，一律视为「无冷静期」。
 */
class CooldownStore(context: Context) {

    private val cooldownFile = File(context.filesDir, FILE_NAME)
    private val tempFile = File(context.filesDir, "$FILE_NAME.tmp")

    /**
     * 同步写入冷静期截止时间戳并强制刷盘。
     * @return true 表示已确认落盘；false 表示落盘失败（调用方应记录但不得崩溃）。
     */
    @Synchronized
    fun write(epochDeadlineMs: Long): Boolean {
        return try {
            FileOutputStream(tempFile).use { out ->
                out.write(epochDeadlineMs.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync() // 强制写入文件系统，最大限度防突然断电丢失
            }
            // 原子替换，避免读到写了一半的文件
            if (tempFile.renameTo(cooldownFile)) {
                true
            } else {
                // 个别 ROM rename 失败时退化为直接覆盖写
                FileOutputStream(cooldownFile).use { out ->
                    out.write(epochDeadlineMs.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    /** 读取冷静期截止时间戳；无记录或非法返回 null。 */
    @Synchronized
    fun readEpochMs(): Long? = try {
        cooldownFile
            .takeIf { it.exists() }
            ?.readText(Charsets.UTF_8)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    } catch (_: Exception) {
        null
    }

    /** 冷静期正常结束后清除标记。 */
    @Synchronized
    fun clear() {
        runCatching { if (cooldownFile.exists()) cooldownFile.delete() }
    }

    private companion object {
        const val FILE_NAME = "cooldown_epoch"
    }
}
