package app.gamanga.desktop.data.backup

/**
 * 极简 protobuf wire format 读取器（F-03：导入 mihon 备份用）。
 *
 * 为什么不引 protobuf 库：mihon 备份（`.tachibk` = gzip + protobuf）里我们只关心
 * 漫画条目和章节进度，字段号是固定的（见下）。手写 varint/length-delimited 解析
 * 约 80 行，零依赖；引一整套 protobuf runtime 只为读几个字段不划算。
 *
 * 字段号依据 mihon `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/`：
 * - Backup：1 = repeated BackupManga
 * - BackupManga：1 source(Long) 2 url 3 title 5 author 9 thumbnailUrl 13 dateAdded
 *   16 chapters(repeated BackupChapter)
 * - BackupChapter：1 url 2 name 4 read(Bool) 6 lastPageRead(Long) 9 chapterNumber(**Float**→wireType 5)
 */

/** protobuf 字段值（按 wire type 分类）。 */
internal sealed class ProtoValue {
    /** wireType 0：varint（int/long/bool/enum）。 */
    data class VarInt(val value: Long) : ProtoValue()

    /** wireType 1：64-bit fixed。 */
    data class Fixed64(val value: Long) : ProtoValue()

    /** wireType 5：32-bit fixed（float / sfixed32）。 */
    data class Fixed32(val value: Int) : ProtoValue()

    /** wireType 2：length-delimited（string / bytes / 嵌套 message / packed repeated）。 */
    data class Bytes(val value: ByteArray) : ProtoValue() {
        override fun equals(other: Any?) = other is Bytes && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
}

/** 一个字段号 + 值（同一字段号多次出现 = repeated）。 */
internal data class ProtoField(val number: Int, val value: ProtoValue)

/**
 * 解析一段 protobuf 字节，返回**按出现顺序**的字段列表。
 *
 * 未知 wire type / 截断数据抛 [IllegalArgumentException]——调用方（备份导入）按
 * "文件不是合法 mihon 备份"处理，不让坏数据半吞半吐。
 */
internal fun parseProtobuf(bytes: ByteArray): List<ProtoField> {
    val out = ArrayList<ProtoField>()
    var i = 0
    while (i < bytes.size) {
        // tag = (fieldNumber << 3) | wireType，varint 编码
        var tag = 0L
        var shift = 0
        while (true) {
            if (i >= bytes.size) throw IllegalArgumentException("truncated tag")
            val b = bytes[i++].toInt() and 0xFF
            tag = tag or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw IllegalArgumentException("tag too long")
        }
        val number = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        when (wireType) {
            0 -> { // varint
                var v = 0L
                var s = 0
                while (true) {
                    if (i >= bytes.size) throw IllegalArgumentException("truncated varint")
                    val b = bytes[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl s)
                    if (b and 0x80 == 0) break
                    s += 7
                    if (s > 63) throw IllegalArgumentException("varint too long")
                }
                out += ProtoField(number, ProtoValue.VarInt(v))
            }
            1 -> { // 64-bit
                if (i + 8 > bytes.size) throw IllegalArgumentException("truncated fixed64")
                var v = 0L
                for (k in 0 until 8) v = (v shl 8) or (bytes[i + k].toLong() and 0xFF)
                i += 8
                out += ProtoField(number, ProtoValue.Fixed64(v))
            }
            2 -> { // length-delimited
                var len = 0
                var s = 0
                while (true) {
                    if (i >= bytes.size) throw IllegalArgumentException("truncated length")
                    val b = bytes[i++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl s)
                    if (b and 0x80 == 0) break
                    s += 7
                    if (s > 31) throw IllegalArgumentException("length too long")
                }
                if (i + len > bytes.size || len < 0) throw IllegalArgumentException("truncated bytes")
                out += ProtoField(number, ProtoValue.Bytes(bytes.copyOfRange(i, i + len)))
                i += len
            }
            5 -> { // 32-bit
                if (i + 4 > bytes.size) throw IllegalArgumentException("truncated fixed32")
                var v = 0
                for (k in 0 until 4) v = (v shl 8) or (bytes[i + k].toInt() and 0xFF)
                i += 4
                out += ProtoField(number, ProtoValue.Fixed32(v))
            }
            else -> throw IllegalArgumentException("unsupported wire type $wireType (field $number)")
        }
    }
    return out
}

// ---- 取值助手：按字段号取第一个/全部 ----

internal fun List<ProtoField>.strings(number: Int): List<String> =
    filter { it.number == number }.mapNotNull { (it.value as? ProtoValue.Bytes)?.value?.decodeToString() }

internal fun List<ProtoField>.string(number: Int): String? = strings(number).firstOrNull()

internal fun List<ProtoField>.long(number: Int): Long? =
    filter { it.number == number }.firstNotNullOfOrNull { (it.value as? ProtoValue.VarInt)?.value }

internal fun List<ProtoField>.bool(number: Int): Boolean? =
    long(number)?.let { it != 0L }

internal fun List<ProtoField>.float(number: Int): Float? =
    filter { it.number == number }.firstNotNullOfOrNull { field ->
        when (val v = field.value) {
            is ProtoValue.Fixed32 -> Float.fromBits(v.value) // wireType 5：正规 float
            // kotlinx.serialization 对 Float 固定用 fixed32，但宽容起见也收 varint
            is ProtoValue.VarInt -> Float.fromBits(v.value.toInt())
            // **double 用 wireType 1**：不同版本的 mihon 备份把 chapterNumber 写成 double 过
            // （2026-10-29 加，见 F-275：旧备份按话数对齐会整体落空，只能靠名字兜底）
            is ProtoValue.Fixed64 -> Double.fromBits(v.value).toFloat()
            else -> null
        }
    }

// ---- 「多候选字段号」取值：旧版本备份的字段号可能挪过位 ----
//
// 用户 2026-10-29 问「很早之前的 mihon 备份，代码还能用吗」。字段号在 Tachiyomi/mihon
// 历代里大体稳定，但个别版本挪过（缩略图 9/13、章节 16/12 都出现过）。
// 这里按候选顺序取第一个有值的，取不到再退到原来的单号读取——**宽容读、严格校验**：
// 只要 url + title 还在，这一部就能救回来。

internal fun List<ProtoField>.stringAny(vararg numbers: Int): String? {
    for (n in numbers) string(n)?.let { return it }
    return null
}

internal fun List<ProtoField>.longAny(vararg numbers: Int): Long? {
    for (n in numbers) long(n)?.let { return it }
    return null
}

/**
 * 所有 wireType=2 的原始字节（不解释内容）。
 *
 * 用途只有一个：**在备份里找封面图**（R-170）。mihon 的 `.tachibk` 正典格式不含图片，
 * 但部分分支/魔改版会把封面塞进某个 bytes 字段，字段号不固定 —— 所以不猜字段号，
 * 直接扫全部字节，靠文件头判是不是图片（[looksLikeImage]）。
 */
internal fun List<ProtoField>.byteBlobs(): List<ByteArray> =
    mapNotNull { (it.value as? ProtoValue.Bytes)?.value }

/** 文件头判图：JPEG / PNG / GIF / WEBP，且至少 512 字节（排除图标、占位色块这类小数据）。 */
internal fun looksLikeImage(bytes: ByteArray): Boolean {
    if (bytes.size < 512) return false
    fun at(i: Int): Int = bytes[i].toInt() and 0xFF
    return when {
        bytes.size > 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true
        bytes.size > 8 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true
        bytes.size > 6 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true
        bytes.size > 12 && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true
        else -> false
    }
}

internal fun List<ProtoField>.messagesAny(vararg numbers: Int): List<List<ProtoField>> {
    for (n in numbers) {
        val hit = messages(n)
        if (hit.isNotEmpty()) return hit
    }
    return emptyList()
}

internal fun List<ProtoField>.messages(number: Int): List<List<ProtoField>> =
    filter { it.number == number }.mapNotNull { (it.value as? ProtoValue.Bytes)?.value?.let(::parseProtobuf) }
