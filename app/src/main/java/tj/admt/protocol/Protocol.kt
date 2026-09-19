package tj.admt.protocol

/**
 * Протокол обмена с приборами ADMT (серия S).
 *
 * Восстановлен из кода приложения Aidu Prospecting 2.11.10,
 * пакет com.aidush.app.measurecontrol.bluetooth.
 *
 * Кадр запроса:
 *   AA | model | sampleMethod | 55 | len | cmd | params... | CS
 *   len = params.size + 2
 *   CS  = (len + cmd + сумма params) & 0xFF
 *
 * Кадр ответа:
 *   55 | model | sampleMethod | AA | len(2 байта) | cmd | data[len-2] | CS
 *
 * ВНИМАНИЕ. В оригинальном коде порядок двух байтов длины в ответе
 * реализован противоречиво: приёмный автомат читает их как little-endian,
 * а разборщик кадра — как big-endian. Какой из двух вариантов реально
 * идёт по проводу, по коду определить нельзя. Поэтому FrameReader
 * определяет порядок сам, по факту схождения контрольной суммы.
 *
 * Сложение байтов в контрольной сумме всегда беззнаковое (b and 0xFF):
 * в Kotlin Byte знаковый, и «sum += frame[i]» иначе вычитает 128..255.
 */
object Cmd {
    const val HANDSHAKE: Byte = 0x10
    const val READ_ELECTRICITY: Byte = 0x20
    const val READ_TIME: Byte = 0x30
    const val WRITE_TIME: Byte = 0x32
    const val READ_EXPIRY: Byte = 0x34
    const val WRITE_EXPIRY: Byte = 0x36
    const val READ_MODEL: Byte = 0x40
    const val WRITE_MODEL: Byte = 0x42
    const val READ_METHODS: Byte = 0x50
    const val READ_SAMPLE_METHOD: Byte = 0x52
    const val WRITE_SAMPLE_METHOD: Byte = 0x54
    const val READ_DATA: Byte = 0x60
    const val READ_DATA_FORCE: Byte = 0x62
    const val READ_DATA_RETRY: Byte = 0x64
    const val READ_DATA_OFFLINE: Byte = 0x66
    const val READ_FILE_DATA: Byte = 0x68
    const val READ_FILE_NUMS: Byte = 0x70
}

object Rsp {
    const val HANDSHAKE: Byte = 0x11
    const val DATA_SCHEDULE: Byte = 0x16
    const val ELECTRICITY: Byte = 0x21
    const val ELECTRICITY2: Byte = 0x22
    const val TIME: Byte = 0x31
    const val TIME_WRITTEN: Byte = 0x33
    const val EXPIRY: Byte = 0x35
    const val EXPIRY_WRITTEN: Byte = 0x37
    const val MODEL: Byte = 0x41
    const val MODEL_WRITTEN: Byte = 0x43
    const val FREQUENCY: Byte = 0x51
    const val SAMPLE_METHOD: Byte = 0x53
    const val SAMPLE_METHOD_WRITTEN: Byte = 0x55
    const val DATA_COMPLETE: Byte = 0x61
    const val DATA: Byte = 0x63
    const val DATA_RETRY: Byte = 0x65
    const val FILE_NUMS: Byte = 0x71
}

const val REQ_START: Byte = 0xAA.toByte()
const val REQ_MARK: Byte = 0x55
const val RSP_START: Byte = 0x55
const val RSP_MARK: Byte = 0xAA.toByte()

private fun Byte.u(): Int = toInt() and 0xFF

/** Собирает кадр запроса к прибору. */
fun buildRequest(
    cmd: Byte,
    model: Byte = 0,
    sampleMethod: Byte = 0,
    params: ByteArray = ByteArray(0)
): ByteArray {
    val len = params.size + 2
    val out = ByteArray(params.size + 7)
    out[0] = REQ_START
    out[1] = model
    out[2] = sampleMethod
    out[3] = REQ_MARK
    out[4] = len.toByte()
    out[5] = cmd
    params.copyInto(out, 6)

    var sum = len + cmd.u()
    for (b in params) sum += b.u()
    out[out.size - 1] = (sum and 0xFF).toByte()
    return out
}

/** Проверяет контрольную сумму принятого кадра. dataLen — длина тела без cmd и CS. */
fun checksumOk(frame: ByteArray, dataLen: Int): Boolean {
    if (dataLen < 0) return false
    if (frame.size < 8 + dataLen) return false
    val cmd = frame[6].u()
    var sum = (dataLen + 2) + cmd
    val dataEnd = 7 + dataLen
    if (dataEnd >= frame.size) return false
    for (i in 7 until dataEnd) sum += frame[i].u()
    return (sum and 0xFF) == frame[frame.size - 1].u()
}

/** 4 байта -> float, порядок little-endian (как ByteUtils.byte2float в оригинале). */
fun bytesToFloatLE(b: ByteArray, off: Int): Float {
    if (off < 0 || off + 3 >= b.size) return Float.NaN
    val bits = (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    return Float.fromBits(bits)
}

fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }
