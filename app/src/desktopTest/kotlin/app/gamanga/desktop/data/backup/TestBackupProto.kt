package app.gamanga.desktop.data.backup

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 测试用的极简 protobuf 编码器（只够拼出 mihon 备份要的结构）。
 *
 * 抽成共享工具是因为有两个测试类要用它造「旧版备份」字节：
 * `MihonImportPlanTest`（解析层）与 `MihonImportModeTest`（导入行为）。
 * 手写编码器的好处是**不依赖被测代码**——用我们自己的 writer 造数据再喂给 reader，
 * 字段号写错了两边一起错，测不出问题。
 */
internal class Proto {
    private val out = ByteArrayOutputStream()

    fun varint(value: Long): Proto {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return this
            }
            out.write(b or 0x80)
        }
    }

    fun tag(field: Int, wire: Int): Proto = varint(((field shl 3) or wire).toLong())

    fun string(field: Int, value: String): Proto {
        val bytes = value.toByteArray(Charsets.UTF_8)
        tag(field, 2).varint(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun bytes(field: Int, block: ByteArray): Proto {
        tag(field, 2).varint(block.size.toLong())
        out.write(block)
        return this
    }

    fun double(field: Int, value: Double): Proto {
        tag(field, 1)
        val bits = value.toRawBits()
        for (k in 7 downTo 0) {
            out.write((((bits ushr (k * 8)) and 0xFF).toInt()))
        }
        return this
    }

    fun bool(field: Int, value: Boolean): Proto = varint(((field shl 3) or 0).toLong()).varint(if (value) 1L else 0L)

    fun long(field: Int, value: Long): Proto = varint(((field shl 3) or 0).toLong()).varint(value)

    fun build(): ByteArray = out.toByteArray()
}

internal fun gzip(bytes: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.toByteArray()
}
