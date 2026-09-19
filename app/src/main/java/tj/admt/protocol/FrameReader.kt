package tj.admt.protocol

/**
 * Собирает кадры из потока байтов, приходящих по Bluetooth.
 *
 * Порядок двух байтов длины в оригинале реализован по-разному в двух местах,
 * поэтому здесь он определяется по контрольной сумме.
 *
 * Пока порядок неизвестен, кадр не отбрасывается после первой неудачной
 * суммы: ждём второй кандидат длины. Если оба варианта уже полностью
 * пришли и оба не сходятся — съедаем стартовый байт и ищем 0x55 заново.
 * После первого успешного кадра порядок фиксируется.
 */
class FrameReader(private val onFrame: (Frame) -> Unit) {

    data class Frame(
        val model: Byte,
        val sampleMethod: Byte,
        val cmd: Byte,
        val data: ByteArray,
        val raw: ByteArray
    )

    enum class LenOrder { UNKNOWN, LITTLE, BIG }

    var lenOrder: LenOrder = LenOrder.UNKNOWN
        private set

    private val buf = ArrayList<Byte>(512)

    /** Защита от мусора: тело длиннее этого — не кадр прибора серии S. */
    private val maxData = 1024

    /**
     * Реальные кадры прибора ADMT-100S не превышают ~80 байт данных (20 float).
     * Если один кандидат длины уже разобран и не сошёлся по CS, а второй ещё
     * не доехал целиком — ждать его целесообразно только если он правдоподобен
     * для этого прибора. Кандидат за сотни байт почти наверняка порождён
     * противоположным порядком байт на мусоре, и ждать его — значит копить
     * буфер до maxData вместо немедленной ресинхронизации.
     */
    private val realisticMax = 256

    private data class Cand(val field: Int, val dataLen: Int, val total: Int)

    fun reset() {
        buf.clear()
    }

    fun feed(b: Byte) {
        buf.add(b)
        if (buf.size > 8192) {
            val idx = buf.indexOf(RSP_START)
            if (idx <= 0) buf.clear() else repeat(idx) { buf.removeAt(0) }
        }
        drain()
    }

    fun feed(bytes: ByteArray, count: Int = bytes.size) {
        val n = count.coerceAtMost(bytes.size)
        for (i in 0 until n) feed(bytes[i])
    }

    private fun u16le(b0: Byte, b1: Byte): Int =
        (b0.toInt() and 0xFF) or ((b1.toInt() and 0xFF) shl 8)

    private fun u16be(b0: Byte, b1: Byte): Int =
        ((b0.toInt() and 0xFF) shl 8) or (b1.toInt() and 0xFF)

    private fun drain() {
        while (true) {
            while (buf.isNotEmpty() && buf[0] != RSP_START) buf.removeAt(0)
            if (buf.size < 8) return

            if (buf.size >= 4 && buf[3] != RSP_MARK) {
                buf.removeAt(0)
                continue
            }

            val little = u16le(buf[4], buf[5])
            val big = u16be(buf[4], buf[5])
            val fields = when (lenOrder) {
                LenOrder.LITTLE -> listOf(little)
                LenOrder.BIG -> listOf(big)
                LenOrder.UNKNOWN -> listOf(little, big).distinct()
            }

            val cands = fields.map { Cand(it, it - 2, 7 + (it - 2) + 1) }
            val plausible = cands.filter { it.dataLen in 0..maxData }
            if (plausible.isEmpty()) {
                buf.removeAt(0)
                continue
            }

            val readyOk = ArrayList<Cand>()
            var waiting = false
            for (c in plausible) {
                if (buf.size < c.total) {
                    if (c.dataLen <= realisticMax) waiting = true
                    continue
                }
                val raw = ByteArray(c.total) { i -> buf[i] }
                if (checksumOk(raw, c.dataLen)) readyOk.add(c)
            }

            if (readyOk.isEmpty()) {
                if (waiting) return
                buf.removeAt(0)
                continue
            }

            val chosen = pick(readyOk, little)
            val raw = ByteArray(chosen.total) { i -> buf[i] }
            if (lenOrder == LenOrder.UNKNOWN) {
                lenOrder = if (chosen.field == little) LenOrder.LITTLE else LenOrder.BIG
            }
            val data = if (chosen.dataLen == 0) ByteArray(0)
            else raw.copyOfRange(7, 7 + chosen.dataLen)
            repeat(chosen.total) { buf.removeAt(0) }
            onFrame(Frame(raw[1], raw[2], raw[6], data, raw))
        }
    }

    /**
     * Если оба порядка дали сходящуюся сумму (редко, короткий мусор),
     * берём длину, типичную для известных ответов прибора.
     */
    private fun pick(ok: List<Cand>, littleField: Int): Cand {
        if (ok.size == 1) return ok[0]
        val typical = setOf(0, 1, 4, 6, 20, 80)
        // minByOrNull возвращает Cand? даже когда список непуст — компилятор
        // не знает, что ok уже проверен на пустоту вызывающей стороной,
        // поэтому финальный ok[0] нужен просто чтобы удовлетворить типы,
        // а не потому что он когда-либо реально сработает.
        return ok.firstOrNull { it.dataLen in typical }
            ?: ok.minByOrNull { kotlin.math.abs(it.field - littleField) + it.dataLen }
            ?: ok[0]
    }
}

/** Разбирает тело ответа 0x63 — массив значений замера, по одному на уровень глубины. */
fun parseMeasureData(data: ByteArray): List<Float> {
    if (data.isEmpty() || data.size % 4 != 0) return emptyList()
    val out = ArrayList<Float>(data.size / 4)
    var i = 0
    while (i + 3 < data.size) {
        val v = bytesToFloatLE(data, i)
        if (v.isNaN() || v.isInfinite()) return emptyList()
        out.add(v)
        i += 4
    }
    return out
}

/** Разбирает ответ 0x21/0x22 — заряд батареи прибора в процентах. */
fun parseBattery(data: ByteArray): Int =
    if (data.isEmpty()) -1 else (data[0].toInt() and 0xFF).coerceIn(0, 100)
