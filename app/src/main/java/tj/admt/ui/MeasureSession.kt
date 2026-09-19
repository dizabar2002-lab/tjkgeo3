package tj.admt.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tj.admt.bt.SppClient
import tj.admt.data.Profile
import tj.admt.data.Storage
import tj.admt.protocol.Cmd
import tj.admt.protocol.FrameReader
import tj.admt.protocol.Rsp
import tj.admt.protocol.buildRequest
import tj.admt.protocol.parseBattery
import tj.admt.protocol.parseMeasureData
import tj.admt.protocol.toHex

/**
 * Ведёт сеанс работы с прибором.
 *
 * Порядок обмена повторяет тот, что зашит в приложении Aidu:
 * рукопожатие -> заряд батареи -> метод опроса -> чтение замера по команде.
 */
class MeasureSession(
    private val scope: CoroutineScope,
    private val storage: Storage
) {
    val state = mutableStateOf("Не подключено")
    val battery = mutableStateOf(-1)
    val handshaken = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val log = mutableStateListOf<String>()
    val lastPoint = mutableStateOf<List<Float>>(emptyList())
    val lastWasRetry = mutableStateOf(false)
    val pointCv = mutableStateOf(0f)
    val pointQuality = mutableStateOf(tj.admt.proc.Physics.Quality.UNKNOWN)

    /** Код из ответа 0x61: 0 — штатно, иначе прибор сообщил о сбое замера. */
    val measureFailedCode = mutableStateOf(0)

    companion object {
        /** Столько ждёт данных оригинальное приложение Aidu. */
        const val MEASURE_TIMEOUT_MS = 1_200_000L
    }

    var profile = mutableStateOf<Profile?>(null)

    /** Модель и метод опроса передаются в каждом кадре запроса. */
    private var model: Byte = 0
    private var sampleMethod: Byte = 0

    private var measureTimeout: Job? = null
    private var pendingReplaceLast = false

    /**
     * Все попытки замера ТЕКУЩЕЙ станции — от первого «Замерить точку»
     * до последнего «Повторить». Очищается при начале новой станции,
     * копится при повторах. По ней считается медиана и cv станции.
     */
    private val repeatBuffer = mutableListOf<List<Float>>()

    private val client = SppClient(
        scope = scope,
        onFrame = { handle(it) },
        onState = { s -> state.value = s; addLog(s) },
        onRawIn = { },
        onUnexpectedDrop = { tryAutoReconnect() }
    )

    private var lastDevice: BluetoothDevice? = null
    private var lastAdapter: BluetoothAdapter? = null
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 3

    val isConnected: Boolean get() = client.isConnected

    private fun addLog(line: String) {
        log.add(0, line)
        while (log.size > 200) log.removeAt(log.size - 1)
    }

    fun paired(adapter: BluetoothAdapter?) = client.pairedDevices(adapter)

    fun connect(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        lastDevice = device
        lastAdapter = adapter
        reconnectAttempt = 0
        scope.launch {
            runCatching { client.connect(device, adapter) }
                .onFailure { state.value = "Ошибка подключения: ${it.message}"; addLog(state.value) }
            if (client.isConnected) {
                delay(300)
                send(Cmd.HANDSHAKE)
            }
        }
    }

    /**
     * Связь оборвалась сама — пробуем восстановить её без участия
     * оператора. Не более maxReconnectAttempts раз, с паузой между
     * попытками; после рукопожатия счётчик сбрасывается снова.
     *
     * Если сеанс закрыт намеренно (кнопкой «Отключить» или уходом с
     * прибора), lastDevice не задан заново, а intentional-флаг в
     * SppClient и не даёт этому колбэку сработать при обычном отключении.
     */
    private fun tryAutoReconnect() {
        val device = lastDevice ?: return
        if (reconnectAttempt >= maxReconnectAttempts) {
            state.value = "Связь потеряна после $maxReconnectAttempts попыток. " +
                "Проверьте заряд прибора и расстояние, затем нажмите «Связь» вручную."
            addLog(state.value)
            return
        }
        reconnectAttempt++
        state.value = "Связь прервана — переподключение ($reconnectAttempt из $maxReconnectAttempts)…"
        addLog(state.value)
        scope.launch {
            delay(3000)
            runCatching { client.connect(device, lastAdapter) }
            if (client.isConnected) {
                delay(300)
                send(Cmd.HANDSHAKE)
            } else {
                tryAutoReconnect()
            }
        }
    }

    fun disconnect() {
        lastDevice = null
        measureTimeout?.cancel()
        busy.value = false
        client.disconnect()
        handshaken.value = false
        battery.value = -1
    }

    private fun send(cmd: Byte, params: ByteArray = ByteArray(0)) {
        val frame = buildRequest(cmd, model, sampleMethod, params)
        addLog("→ ${frame.toHex()}")
        scope.launch { client.send(frame) }
    }

    fun readBattery() = send(Cmd.READ_ELECTRICITY)
    fun readModel() = send(Cmd.READ_MODEL)
    fun readSampleMethod() = send(Cmd.READ_SAMPLE_METHOD)

    /** Замер НОВОЙ точки профиля — начинает станцию заново. */
    fun measurePoint() {
        if (!client.isConnected) { state.value = "Нет связи с прибором"; return }
        pendingReplaceLast = false
        lastWasRetry.value = false
        repeatBuffer.clear()
        startMeasureWait("Замер…")
        send(Cmd.READ_DATA)
    }

    /**
     * Повтор ТЕКУЩЕЙ станции: новый замер добавляется в repeatBuffer,
     * а в профиль пишется медиана по всем накопленным повторам — не
     * последнее сырое значение. Разброс между повторами (cv) — честная
     * оценка, насколько можно доверять точке; см. Physics.kt.
     */
    fun retryPoint() {
        if (!client.isConnected) { state.value = "Нет связи с прибором"; return }
        pendingReplaceLast = profile.value?.pointCount ?: 0 > 0
        lastWasRetry.value = true
        startMeasureWait("Повтор…")
        send(Cmd.READ_DATA_RETRY)
    }

    /**
     * Принудительное чтение — используется, когда обычный замер не
     * отвечает. Не участвует в стекинге станции: если 0x60 не сработал,
     * накопленные до этого попытки той же станции всё равно ненадёжны,
     * поэтому здесь чище начать станцию заново, чем мешать в одну
     * медиану удачные и неудачные протоколы чтения.
     */
    fun forceRead() {
        if (!client.isConnected) { state.value = "Нет связи с прибором"; return }
        pendingReplaceLast = false
        lastWasRetry.value = false
        repeatBuffer.clear()
        startMeasureWait("Принудительное чтение…")
        send(Cmd.READ_DATA_FORCE)
    }

    private fun startMeasureWait(label: String) {
        busy.value = true
        measureFailedCode.value = 0
        // Чистый буфер разбора перед каждой командой замера: остатки
        // предыдущего обмена к новой станции отношения не имеют.
        client.resetParser()
        state.value = label
        measureTimeout?.cancel()
        measureTimeout = scope.launch {
            // Оригинальное приложение Aidu ждёт замер до 20 минут
            // (sendToBlu(..., 1200000L)), а его строка progress_measuring
            // прямо говорит «подождите 2–10 минут». Прежние 90 секунд были
            // взяты наугад и обрывали нормальный долгий замер.
            delay(MEASURE_TIMEOUT_MS)
            if (busy.value) {
                busy.value = false
                state.value = "Нет ответа от прибора за 20 минут. Проверьте связь и повторите."
            }
        }
    }

    private fun finishMeasure() {
        measureTimeout?.cancel()
        busy.value = false
    }

    private fun handle(f: FrameReader.Frame) {
        val dataHex = if (f.data.isNotEmpty()) " [${f.data.toHex()}]" else ""
        addLog("← cmd %02X, %d байт%s".format(f.cmd.toInt() and 0xFF, f.data.size, dataHex))
        when (f.cmd) {
            Rsp.HANDSHAKE -> {
                handshaken.value = true
                reconnectAttempt = 0
                state.value = "Прибор на связи"
                model = f.model
                sampleMethod = f.sampleMethod
                readBattery()
                readModel()
                readSampleMethod()
            }
            Rsp.ELECTRICITY, Rsp.ELECTRICITY2 -> {
                battery.value = parseBattery(f.data)
                state.value = "Батарея прибора: ${battery.value}%"
                if (battery.value in 0..15) {
                    addLog("Низкий заряд прибора — замер может оборваться")
                }
            }
            Rsp.MODEL -> {
                model = f.data.firstOrNull() ?: f.model
                addLog("Модель прибора: код ${model.toInt() and 0xFF}")
            }
            Rsp.SAMPLE_METHOD -> {
                sampleMethod = f.data.firstOrNull() ?: f.sampleMethod
                addLog("Метод опроса: код ${sampleMethod.toInt() and 0xFF}")
            }
            Rsp.DATA_SCHEDULE -> {
                val pct = f.data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                state.value = "Замер… $pct%"
            }
            Rsp.DATA, Rsp.DATA_RETRY -> {
                val values = parseMeasureData(f.data)
                if (values.isEmpty()) {
                    state.value = "Данные не разобраны (${f.data.size} байт)"
                    finishMeasure()
                    return
                }
                lastPoint.value = values
                repeatBuffer.add(values)

                val (median, cv) = tj.admt.proc.Physics.stackStation(repeatBuffer)
                val quality = tj.admt.proc.Physics.stationQuality(repeatBuffer.size, cv)
                pointCv.value = cv
                pointQuality.value = quality

                val p = profile.value
                if (p != null) {
                    if (p.levels != median.size && p.pointCount == 0) p.levels = median.size
                    if (pendingReplaceLast && p.pointCount > 0) p.replaceLastPoint(median, cv)
                    else p.addPoint(median, cv)
                    pendingReplaceLast = false
                    storage.save(p)
                    val qLabel = tj.admt.proc.Physics.qualityLabel(quality)
                    state.value = if (repeatBuffer.size > 1)
                        "Точка ${p.pointCount}: повторов ${repeatBuffer.size}, cv ${"%.0f".format(cv)}% — $qLabel"
                    else
                        "Точка ${p.pointCount} записана, уровней ${median.size}"
                } else {
                    state.value = "Получено ${values.size} значений, профиль не выбран"
                }
                finishMeasure()
            }
            Rsp.DATA_COMPLETE -> {
                // 0x61 несёт ОДИН байт — код результата замера.
                // Разобрано из оригинального приложения Aidu 2.11.10
                // (MeasureViewModel, обработка ReadDataCompleteRes):
                //   код == 0  -> замер идёт штатно, данные придут кадром 0x63
                //   код != 0  -> ЗАМЕР НЕ УДАЛСЯ, 0x63 не придёт никогда.
                // Оригинал в этом случае показывает диалог «Сбой измерения»
                // с кодом ошибки и предлагает принудительный замер (0x62).
                // Типовая причина по их же строке mn_big: обрыв цепи MN
                // или слишком большое сопротивление заземления электродов.
                val code = if (f.data.isNotEmpty()) f.data[0].toInt() and 0xFF else 0
                if (code != 0) {
                    measureFailedCode.value = code
                    state.value = "Сбой измерения, код $code — вероятно обрыв цепи MN " +
                        "или плохое заземление электродов"
                    addLog(state.value)
                    finishMeasure()
                } else {
                    state.value = "Замер идёт, ждём данные (2–10 минут)…"
                }
            }
            else -> addLog("Ответ без обработчика: %02X".format(f.cmd.toInt() and 0xFF))
        }
    }
}
