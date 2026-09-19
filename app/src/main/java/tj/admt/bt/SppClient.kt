package tj.admt.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tj.admt.protocol.FrameReader
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Классический Bluetooth SPP (RFCOMM).
 * Тот же профиль и тот же UUID, что использует приложение Aidu.
 *
 * Колбэки onFrame / onState вызываются на Main, чтобы Compose-состояние
 * не трогалось с потока чтения.
 */
@SuppressLint("MissingPermission")
class SppClient(
    private val scope: CoroutineScope,
    private val onFrame: (FrameReader.Frame) -> Unit,
    private val onState: (String) -> Unit,
    private val onRawIn: (ByteArray) -> Unit = {},
    /**
     * Вызывается, когда связь пропала САМА — не потому, что вызвали
     * disconnect(). Обрыв SPP-сокета после успешного подключения — обычное
     * дело для дешёвых Bluetooth-модулей (уход в сон, помехи, посадка
     * батареи прибора). MeasureSession подписывается сюда, чтобы пробовать
     * переподключиться самой, не заставляя оператора в поле лезть на
     * вкладку «Прибор» и жать «Связь» заново.
     */
    private val onUnexpectedDrop: () -> Unit = {}
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var readJob: Job? = null
    private val reader = FrameReader { frame ->
        scope.launch(Dispatchers.Main) { onFrame(frame) }
    }

    /**
     * true — сокет закрывает сам disconnect() (по кнопке или как чистка
     * перед новым connect()). false — закрытие пришло само, из чтения.
     * Различать обязательно: иначе нажатие «Отключить» тут же запускало
     * бы автопереподключение.
     */
    private var intentional = false

    val isConnected: Boolean get() = socket?.isConnected == true

    /**
     * Сбрасывает буфер разбора кадров.
     *
     * Приёмник и сам восстанавливается после оборванного кадра — как
     * только доберёт байты до заявленной длины, контрольная сумма не
     * сойдётся и он ресинхронизируется, потеряв только сам обрывок.
     * Но перед новой станцией дешевле начать с заведомо чистого буфера,
     * чем полагаться на это: всё, что осталось от прошлой команды,
     * к новому замеру отношения не имеет.
     */
    fun resetParser() {
        reader.reset()
    }

    fun pairedDevices(adapter: BluetoothAdapter?): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    suspend fun connect(device: BluetoothDevice, adapter: BluetoothAdapter?) {
        disconnect()
        intentional = false
        withContext(Dispatchers.IO) {
            adapter?.cancelDiscovery()
            postState("Подключение к ${device.name ?: device.address}")

            val s = openWithTimeout {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            } ?: openWithTimeout {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            } ?: throw java.io.IOException("Не удалось подключиться за ${CONNECT_TIMEOUT_MS / 1000} с")

            socket = s
            out = s.outputStream
            reader.reset()
            postState("Подключено")
            startReading(s.inputStream)
        }
    }

    /**
     * `withTimeout` не прерывает блокирующий `socket.connect()` — таймаут
     * коротины истекает, а поток остаётся висеть в системном вызове.
     * Поэтому таймер закрывает сокет параллельной корутиной: `connect()`
     * получает IOException и разблокируется по-настоящему.
     */
    private suspend fun openWithTimeout(create: () -> BluetoothSocket): BluetoothSocket? {
        val candidate = try {
            create()
        } catch (e: Exception) {
            return null
        }
        val watchdog = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(CONNECT_TIMEOUT_MS)
            runCatching { candidate.close() }
        }
        return try {
            candidate.connect()
            watchdog.cancel()
            candidate
        } catch (e: Exception) {
            watchdog.cancel()
            runCatching { candidate.close() }
            null
        }
    }

    private fun startReading(input: InputStream) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    onRawIn(chunk.copyOf(n))
                    reader.feed(chunk, n)
                }
                if (!intentional) {
                    postState("Связь закрыта прибором")
                    scope.launch(Dispatchers.Main) { onUnexpectedDrop() }
                }
            } catch (e: Exception) {
                if (!intentional) {
                    postState("Связь прервана: ${e.message}")
                    scope.launch(Dispatchers.Main) { onUnexpectedDrop() }
                }
            }
        }
    }

    suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val stream = out ?: run {
                postState("Нет сокета для отправки")
                return@withContext
            }
            stream.write(frame)
            stream.flush()
        } catch (e: Exception) {
            postState("Не отправлено: ${e.message}")
        }
    }

    fun disconnect() {
        intentional = true
        readJob?.cancel()
        readJob = null
        // закрытие сокета разблокирует input.read()
        runCatching { out?.close() }
        runCatching { socket?.close() }
        socket = null
        out = null
        reader.reset()
        postState("Отключено")
    }

    private fun postState(s: String) {
        scope.launch(Dispatchers.Main) { onState(s) }
    }
}
