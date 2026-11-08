package com.shanshui.performance.memory

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.shanshui.performance.network.MemoryMetricEvent
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.PriorityQueue

/** 封存事件及本地调度状态；重试不改写事件或有效期。 */
internal data class MemoryQueuedRecord(
    /** 原运行事件。 */ val event: MemoryMetricEvent,
    /** 本地入队时间，仅用于调度顺序。 */ val createdAtMillis: Long,
    /** 已失败尝试次数。 */ val attempts: Int = 0,
    /** 下次允许发送的时刻。 */ val nextAttemptAtMillis: Long = 0L,
)

/** 已读取的封存文件。 */
internal data class MemoryQueueItem(
    /** 完整事件和重试状态。 */ val record: MemoryQueuedRecord,
    /** 所属文件。 */ val file: File,
)

/** current 与封存队列共用锁；跨 Reporter 接管和上传均不能越过封存边界。 */
internal class MemoryEventStore(
    /** 内存指标专属目录。 */ private val root: File,
    /** current、历史及隔离文件总配额。 */ private val diskQuotaBytes: Long,
    /** 按原观测截止计算的历史窗口。 */ private val eventTtlMillis: Long,
    /** 尝试上限。 */ private val maxAttempts: Int,
    /** 脱敏关键节点日志。 */ private val logger: (() -> String) -> Unit = {},
    /** 固定事件序列化器。 */ private val gson: Gson = GsonBuilder().disableHtmlEscaping().create(),
    /** 隔离文件标识。 */ private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** 可控时钟。 */ private val clock: () -> Long = { System.currentTimeMillis() },
    /** 可注入可靠文件操作。 */ private val atomic: MemoryAtomicFile = MemoryAtomicFile(),
) {
    /** 同一目录的所有 Reporter 实例共享互斥。 */
    private val lock = synchronized(locks) { locks.getOrPut(root.absolutePath) { DirectoryState() } }
    /** 当前轮完整快照。 */ private val current = File(root, "current.json")
    /** 仅此目录参与上传。 */ private val events = File(root, "events")
    /** 隔离目录共用配额。 */ private val dead = File(root, "dead-letter")

    /** 延迟到后台首次操作才创建目录，构造器不进行磁盘 IO。 */
    private fun ensureDirectories() {
        require(events.isDirectory || events.mkdirs()) { "Unable to create memory event directory" }
        require(dead.isDirectory || dead.mkdirs()) { "Unable to create memory dead-letter directory" }
    }

    /** 恢复本轮或先封存旧轮；失败抛出并保留 current，调用方必须重试后才开始采样。 */
    fun restore(processId: String): MemoryMetricEvent? = synchronized(lock) {
        ensureDirectories()
        listEvents()
        /** 待校验或持久化的完整 JSON。 */
        val text = atomic.read(current) ?: return@synchronized null
        /** 本轮原身份及独立峰值的完整信封。 */
        val event = parseEvent(text)
        if (event == null) {
            isolate(current, "invalid_current")
            return@synchronized null
        }
        if (event.processId == processId) {
            logger { "memory current restored eventId=${event.eventId}" }
            return@synchronized event
        }
        if (expired(event, clock())) {
            isolate(current, "expired_current")
            return@synchronized null
        }
        logFailure(event.eventId, "seal") {
            // current 仍在磁盘时保护同 ID 的封存副本，避免配额或上传先删除它。
            val target = eventFile(event.eventId)
            /** 已存在的持久内容，用于幂等核对。 */
            val existing = atomic.read(target)
            if (existing != null) {
                /** 封存事件及其本地重试状态。 */
                val record = parseRecord(existing)
                if (record == null || record.event != event) {
                    isolate(target, "conflicting_seal")
                    logger { "memory conflicting seal isolated eventId=${event.eventId}" }
                }
            }
            if (!target.exists()) writeRecord(target, MemoryQueuedRecord(event, clock()), protectCurrent = true)
            check(parseRecord(atomic.read(target) ?: "")?.event == event) { "memory seal verification failed" }
            atomic.remove(current)
            logger { "memory run sealed eventId=${event.eventId}" }
            null
        }
    }

    /** 保存当前轮；磁盘失败不破坏上一份已提交快照。 */
    fun saveCurrent(event: MemoryMetricEvent) = synchronized(lock) {
        ensureDirectories()
        require(valid(event)) { "invalid memory peak snapshot" }
        /** 待校验或持久化的完整 JSON。 */
        val text = gson.toJson(event)
        logFailure(event.eventId, "save") {
            reserve(current, text.toByteArray(Charsets.UTF_8).size.toLong(), event.eventId)
            atomic.write(current, text)
        }
        logger { "memory current saved eventId=${event.eventId} lastSampledAt=${event.occurredAt}" }
    }

    /** 读取快照用于诊断和测试，不把它加入上传选择。 */
    fun currentEvent(): MemoryMetricEvent? = synchronized(lock) { ensureDirectories(); atomic.read(current)?.let(::parseEvent) }

    /** 直接加入已封存事件；返回值仅在可靠文件确实存在时为 true。 */
    fun enqueue(event: MemoryMetricEvent, createdAtMillis: Long = clock()): Boolean = synchronized(lock) {
        require(valid(event)) { "invalid memory peak event" }
        /** 本次文件操作的目标路径。 */
        val target = eventFile(event.eventId)
        if (atomic.read(target) != null) { refreshCount(); return@synchronized false }
        writeRecord(target, MemoryQueuedRecord(event, createdAtMillis), protectCurrent = true)
        true
    }

    /** 仅选择历史轮并遵守退避，最多保留一批对象，不把整个历史队列反序列化后常驻内存。 */
    fun peekBatch(limit: Int, nowMillis: Long = clock()): List<MemoryQueueItem> = synchronized(lock) {
        require(limit in 1..50) { "memory batch limit must be between 1 and 50" }
        /** 仍由 current 保护的事件 ID，不得提前发送或淘汰。 */
        val protectedId = currentEvent()?.eventId
        /** 与原批次一致的稳定入队顺序。 */
        val order = compareBy<MemoryQueueItem> { it.record.createdAtMillis }.thenBy { it.record.event.eventId }
        /** 最大堆淘汰最晚候选，内存中始终最多一批完整事件。 */
        val selected = PriorityQueue(limit, order.reversed())
        listEvents().forEach { file ->
            /** 逐个读取，未被选中的完整 JSON 对象不留在列表中。 */
            val item = readRecord(file) ?: return@forEach
            if (item.record.event.eventId == protectedId || expired(item.record.event, nowMillis) || item.record.nextAttemptAtMillis > nowMillis) return@forEach
            if (selected.size < limit) selected.add(item) else if (order.compare(item, selected.peek()) < 0) {
                selected.poll()
                selected.add(item)
            }
        }
        selected.sortedWith(order)
    }

    /** 返回后台维护的 events 文件计数，主线程读取不扫描、恢复或改写文件。 */
    fun count(): Int = lock.eventCount

    /** 仅 accepted/duplicate 之后删除，并记录可关联成功日志。 */
    fun acknowledge(eventIds: Collection<String>) = synchronized(lock) {
        eventIds.forEach { id ->
            try { atomic.remove(eventFile(id)) } finally { refreshCount() }
            logger { "memory event acknowledged eventId=$id" }
        }
    }

    /** 保存退避状态；重新读取持久版本避免跨实例在途响应覆盖次数，返回新增耗尽隔离数。 */
    fun markRetry(items: Collection<MemoryQueueItem>, nextAttemptAtMillis: Long): Int = synchronized(lock) {
        /** 另一在途失败已经增加次数时，由存储原子判断真正的耗尽边界。 */
        var exhaustedCount = 0
        items.forEach { item ->
            if (item.file.exists()) {
                logFailure(item.record.event.eventId, "retry") {
                    /** 使用磁盘最新重试状态，不能依据发送前的旧副本把次数或退避回退。 */
                    val current = readRecord(item.file)?.record
                    if (current != null) {
                        check(current.event == item.record.event) { "memory queued event changed during request" }
                        if (current.attempts + 1 >= maxAttempts) {
                            isolate(item.file, "retry_exhausted")
                            exhaustedCount++
                        } else {
                            writeRecord(item.file, current.copy(attempts = current.attempts + 1,
                                nextAttemptAtMillis = maxOf(current.nextAttemptAtMillis, nextAttemptAtMillis)), true)
                            logger { "memory event retry saved eventId=${item.record.event.eventId}" }
                        }
                    }
                }
            }
        }
        enforceQuota()
        exhaustedCount
    }

    /** 永久拒绝和耗尽隔离；不打印载荷。 */
    fun moveToDeadLetter(items: Collection<MemoryQueueItem>, reason: String) = synchronized(lock) {
        items.forEach { if (it.file.exists()) isolate(it.file, reason) }
        enforceQuota()
    }

    /** 历史 TTL 以 lastSampledAt 为准，活动 current 永不因此被删除。 */
    fun expireOlderThan(nowMillis: Long = clock()) = synchronized(lock) {
        /** 仍由 current 保护的事件 ID，不得提前发送或淘汰。 */
        val protectedId = currentEvent()?.eventId
        listEvents().forEach { file ->
            /** 流式检查 TTL，避免恢复阶段同时持有全部事件。 */
            val item = readRecord(file) ?: return@forEach
            if (item.record.event.eventId != protectedId && expired(item.record.event, nowMillis)) {
                atomic.remove(item.file)
                logger { "memory expired event removed eventId=${item.record.event.eventId}" }
            }
        }
        dead.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
            /** 待校验或持久化的完整 JSON。 */
            val text = atomic.read(file).orEmpty()
            /** 原观测截止，损坏记录才退回文件时间。 */
            val end = parseRecord(text)?.event?.occurredAt ?: parseEvent(text)?.occurredAt ?: file.lastModified()
            if (end <= nowMillis - eventTtlMillis) atomic.remove(file)
        }
        enforceQuota()
    }

    /** 对原时间使用闭合过期边界，封存及重试均不续期。 */
    private fun expired(event: MemoryMetricEvent, now: Long): Boolean = event.memoryPeakSummary.lastSampledAt <= now - eventTtlMillis

    /** 读取并严格检查新协议；旧 memory_sample 不进行转换。 */
    private fun readRecord(file: File): MemoryQueueItem? {
        /** 待校验或持久化的完整 JSON。 */
        val text = atomic.read(file) ?: return null
        /** 封存事件及其本地重试状态。 */
        val record = parseRecord(text)
        if (record == null || file.name != "${record.event.eventId}.json") {
            isolate(file, "invalid_or_legacy_event")
            return null
        }
        return MemoryQueueItem(record, file)
    }

    /** Gson 可能绕过 Kotlin 非空约束，所有完整性检查均在保护边界内执行。 */
    private fun parseRecord(text: String): MemoryQueuedRecord? = runCatching {
        /** 用于检查字段类型的 JSON 树。 */
        val json = JsonParser.parseString(text).asJsonObject
        check(parseEvent(json.getAsJsonObject("event").toString()) != null)
        listOf("createdAtMillis", "attempts", "nextAttemptAtMillis").forEach { requireInteger(json, it) }
        gson.fromJson(json, MemoryQueuedRecord::class.java).takeIf { it.createdAtMillis >= 0 && it.attempts in 0..100 && it.nextAttemptAtMillis >= 0 }
    }.getOrNull()

    /** 反序列化后校验完整快照。 */
    private fun parseEvent(text: String): MemoryMetricEvent? = runCatching {
        /** 用于检查字段类型的 JSON 树。 */
        val json = JsonParser.parseString(text).asJsonObject
        check(!json.has("memorySample"))
        listOf("schemaVersion", "occurredAt", "versionCode").forEach { requireInteger(json, it) }
        listOf("eventId", "eventType", "sessionId", "processId", "anonymousDeviceId", "packageName", "appVersion", "buildId", "environment", "channel", "osVersion", "deviceModel").forEach {
            check(json[it]?.isJsonPrimitive == true && json[it].asJsonPrimitive.isString)
        }
        /** 原运行区间与三项独立峰值。 */
        val summary = json.getAsJsonObject("memoryPeakSummary")
        check(summary["processName"]?.isJsonPrimitive == true && summary["processName"].asJsonPrimitive.isString)
        requireInteger(summary, "collectionStartedAt")
        requireInteger(summary, "lastSampledAt")
        listOf("pss", "vss", "javaHeap").forEach { name ->
            summary[name]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { peak ->
                requireInteger(peak, "bytes")
                requireInteger(peak, "occurredAt")
                check(peak["foreground"]?.isJsonPrimitive == true && peak["foreground"].asJsonPrimitive.isBoolean)
                peak["scene"]?.takeUnless { it.isJsonNull }?.let { check(it.isJsonPrimitive && it.asJsonPrimitive.isString) }
            }
        }
        gson.fromJson(json, MemoryMetricEvent::class.java).takeIf(::valid)
    }.getOrNull()

    /** 禁止 Gson 把丢失、字符串或小数字段静默转换为合法零值。 */
    private fun requireInteger(json: JsonObject, name: String) {
        /** 当前待检查的字段值。 */
        val value = json[name]
        check(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("[0-9]+")))
        value.asBigDecimal.longValueExact()
    }

    /** 拒绝损坏快照、空峰值、非法时间和身份；不上传半份 JSON。 */
    private fun valid(event: MemoryMetricEvent): Boolean = runCatching {
        /** 原运行区间与三项独立峰值。 */
        val summary = event.memoryPeakSummary
        event.schemaVersion == 2 && event.eventType == "memory_peak_summary" &&
            uuidV4(event.eventId) && uuidV4(event.processId) &&
            event.sessionId == event.processId && event.packageName.isNotBlank() && event.buildId.isNotBlank() &&
            event.anonymousDeviceId.isNotBlank() && event.appVersion.isNotBlank() && event.osVersion.isNotBlank() &&
            event.deviceModel.isNotBlank() && event.environment.isNotBlank() && event.channel.isNotBlank() &&
            summary.processName.isNotBlank() && summary.processName.length <= 256 &&
            summary.collectionStartedAt >= 0 && summary.lastSampledAt >= summary.collectionStartedAt && event.occurredAt == summary.lastSampledAt &&
            listOfNotNull(summary.pss, summary.vss, summary.javaHeap).let { peaks ->
                peaks.isNotEmpty() && peaks.all { it.bytes in 0..MemorySampler.MAX_MEMORY_BYTES && it.occurredAt in summary.collectionStartedAt..summary.lastSampledAt && (it.scene == null || it.scene.isNotBlank() && it.scene.length <= 128 && it.scene.none(Char::isISOControl)) }
            }
    }.getOrDefault(false)

    /** UUID v4 身份必须使用完整规范形式，拒绝解析器容许的缩写。 */
    private fun uuidV4(value: String): Boolean {
        /** 解析后的规范 UUID，用于验证版本与完整形式。 */
        val uuid = UUID.fromString(value)
        return uuid.version() == 4 && uuid.variant() == 2 && uuid.toString().equals(value, ignoreCase = true)
    }

    /** 可靠写入前预留持久空间，封存时始终保护 current 及同 ID 历史。 */
    private fun writeRecord(file: File, record: MemoryQueuedRecord, protectCurrent: Boolean) {
        /** 待校验或持久化的完整 JSON。 */
        val text = gson.toJson(record)
        reserve(file, text.toByteArray(Charsets.UTF_8).size.toLong(), if (protectCurrent) currentEvent()?.eventId else null)
        try { atomic.write(file, text) } finally { refreshCount() }
    }

    /** 优先清理隔离及最老历史；不足时失败，绝不牺牲唯一 current。 */
    private fun reserve(target: File, bytes: Long, protectedId: String?) {
        /** 预计提交后的受控文件总字节数。 */
        var total = ownedFiles().sumOf { it.length() } - target.length() + bytes
        /** 按隔离优先、历史从旧到新排列的清理候选。 */
        val candidates = dead.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() } +
            listEvents().filter { it != target && it.nameWithoutExtension != protectedId }.sortedBy { it.lastModified() }
        for (file in candidates) {
            if (total <= diskQuotaBytes) break
            /** 删除前记录文件字节数，用于配额扣减。 */
            val length = file.length()
            if (file.delete()) {
                total -= length
                logger { "memory quota removed file=${file.name}" }
            }
        }
        refreshCount()
        if (total > diskQuotaBytes) throw IOException("memory quota cannot preserve current and sealed event")
    }

    /** 清理超额历史，current 占满时保留并记录。 */
    private fun enforceQuota() {
        runCatching { reserve(current, current.length(), currentEvent()?.eventId) }
            .onFailure { logger { "memory quota retains current type=${it.javaClass.simpleName}" } }
    }

    /** 隔离失败必须阻止覆盖原文件；不存在删除原件的兜底。 */
    private fun isolate(file: File, reason: String) {
        atomic.read(file)
        /** 仅允许安全路径字符的隔离原因。 */
        val safeReason = reason.replace(Regex("[^A-Za-z0-9._-]"), "_").take(128)
        /** 本次文件操作的目标路径。 */
        val target = File(dead, "${file.nameWithoutExtension.take(48)}-$safeReason-${idGenerator().take(36)}.json")
        if (!file.renameTo(target)) throw IOException("memory isolation failed")
        // 批量隔离旧样本时不能每移动一个文件就重新扫描整个目录。
        if (file.parentFile == events) lock.eventCount = (lock.eventCount - 1).coerceAtLeast(0)
        logger { "memory file isolated file=${file.name} reason=$safeReason" }
    }

    /** 恢复写入中断的重试文件并仅列出已提交事件。 */
    private fun listEvents(): List<File> {
        events.listFiles { file -> file.name.endsWith(".json.bak") || file.name.endsWith(".json.new") }.orEmpty()
            .map { File(it.path.removeSuffix(".bak").removeSuffix(".new")) }.distinct().forEach { atomic.read(it) }
        refreshCount()
        return events.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().toList()
    }

    /** 只由后台持锁路径刷新可见计数；格式校验及隔离在恢复/上传扫描时完成。 */
    private fun refreshCount() {
        lock.eventCount = events.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty().size
    }

    /** 本内存目录的配额归属，不扫描其他组件。 */
    private fun ownedFiles(): List<File> = listEvents() + dead.listFiles().orEmpty().filter { it.isFile } + listOf(current).filter { it.exists() }

    /** 防止事件 ID 越界访问目录。 */
    private fun eventFile(id: String): File {
        ensureDirectories()
        require(id.matches(ID_PATTERN))
        return File(events, "$id.json")
    }

    /** 关键持久化失败输出可关联 ID 和错误类型，不输出信封或凭据。 */
    private fun <T> logFailure(eventId: String, action: String, block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        logger { "memory $action failed eventId=$eventId type=${failure.javaClass.simpleName}" }
        throw failure
    }

    private companion object {
        /** 路径安全字符，不把 JSON 字段直接当路径。 */ val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        /** 主进程内按目录共享锁和后台维护的可见计数。 */ val locks = mutableMapOf<String, DirectoryState>()
    }

    /** 同一目录跨 Reporter 实例共享的最小运行状态，不包含事件内容。 */
    private class DirectoryState {
        /** 恢复及文件变更后刷新，读线程无需磁盘 IO；启动恢复前暂为零。 */
        @Volatile var eventCount: Int = 0
    }
}
