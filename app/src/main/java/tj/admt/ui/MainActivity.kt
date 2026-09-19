package tj.admt.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import tj.admt.data.Profile
import tj.admt.data.Storage
import kotlinx.coroutines.launch
import tj.admt.proc.AiReport
import tj.admt.proc.Physics
import tj.admt.proc.Export
import tj.admt.proc.Processing
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var storage: Storage
    private lateinit var session: MeasureSession
    private var adapter: BluetoothAdapter? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val ok = granted.values.all { it }
            if (!ok && ::session.isInitialized) {
                session.state.value = "Нет разрешения Bluetooth. Дайте его в настройках приложения."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        storage = Storage(this)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        requestPerms()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val scope = rememberCoroutineScope()
                remember { session = MeasureSession(scope, storage); session }
                // LaunchedEffect(Unit), а не прямое присваивание в теле композабла:
                // иначе строка статуса перезаписывалась бы на КАЖДОЙ рекомпозиции
                // всего дерева (в том числе не связанной с Bluetooth), затирая
                // более свежие сообщения — например об ошибке подключения.
                LaunchedEffect(Unit) {
                    if (adapter?.isEnabled == false) {
                        session.state.value = "Включите Bluetooth в системе"
                    }
                }
                AppScreens(session, storage, adapter, ::share)
            }
        }
    }

    private fun requestPerms() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permLauncher.launch(perms)
    }

    private fun share(name: String, content: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name).apply { writeText(content) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Отправить $name"))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) session.disconnect()
    }
}

private enum class Tab { Device, Measure, Profiles, Plot, Volume }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreens(
    session: MeasureSession,
    storage: Storage,
    adapter: BluetoothAdapter?,
    share: (String, String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.Device) }
    var profiles by remember { mutableStateOf(storage.list()) }
    val current = session.profile.value

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("ADMT", style = MaterialTheme.typography.titleMedium)
                    Text(session.state.value, style = MaterialTheme.typography.bodySmall)
                }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.Device, { tab = Tab.Device },
                    icon = {}, label = { Text("Прибор") })
                NavigationBarItem(tab == Tab.Measure, { tab = Tab.Measure },
                    icon = {}, label = { Text("Замер") })
                NavigationBarItem(tab == Tab.Profiles, { tab = Tab.Profiles; profiles = storage.list() },
                    icon = {}, label = { Text("Профили") })
                NavigationBarItem(tab == Tab.Plot, { tab = Tab.Plot },
                    icon = {}, label = { Text("Разрез") })
                NavigationBarItem(tab == Tab.Volume, { tab = Tab.Volume; profiles = storage.list() },
                    icon = {}, label = { Text("3D") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Device -> DeviceTab(session, adapter)
                Tab.Measure -> MeasureTab(session, storage) { profiles = storage.list() }
                Tab.Profiles -> ProfilesTab(profiles, session, storage, share) { profiles = storage.list() }
                Tab.Plot -> current?.let { PlotTab(it) } ?: EmptyHint("Выберите профиль на вкладке «Профили».")
                Tab.Volume -> VolumeTab(profiles)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, Modifier.padding(32.dp))
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceTab(session: MeasureSession, adapter: BluetoothAdapter?) {
    val devices = remember { session.paired(adapter) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Сопряжённые устройства", style = MaterialTheme.typography.titleSmall)
        Text(
            "Сопряжение выполняется в системных настройках Bluetooth. Здесь только подключение.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        if (adapter?.isEnabled == false) {
            Text("Bluetooth выключен. Включите его в настройках телефона.")
        }

        if (devices.isEmpty()) {
            Text("Устройств нет. Сопрягите прибор в настройках телефона.")
        }
        devices.forEach { d ->
            ListItem(
                headlineContent = { Text(d.name ?: "без имени") },
                supportingContent = { Text(d.address) },
                trailingContent = {
                    Button(onClick = { session.connect(d, adapter) }) { Text("Связь") }
                }
            )
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { session.readBattery() }, enabled = session.isConnected) { Text("Батарея") }
            OutlinedButton(onClick = { session.readModel() }, enabled = session.isConnected) { Text("Модель") }
            OutlinedButton(onClick = { session.disconnect() }, enabled = session.isConnected) { Text("Отключить") }
        }

        if (session.battery.value >= 0) {
            Spacer(Modifier.height(8.dp))
            Text("Заряд прибора: ${session.battery.value}%")
            if (session.battery.value <= 15) {
                Text("Низкий заряд прибора — не начинайте длинный профиль.")
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Журнал обмена", style = MaterialTheme.typography.titleSmall)
        session.log.take(40).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MeasureTab(session: MeasureSession, storage: Storage, onChanged: () -> Unit) {
    val p = session.profile.value
    var name by remember { mutableStateOf("Профиль ${System.currentTimeMillis() % 1000}") }
    var depth by remember { mutableStateOf("100") }
    var spacing by remember { mutableStateOf("5") }
    var mn by remember { mutableStateOf("10") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (p == null) {
            Text("Новый профиль", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(name, { name = it }, label = { Text("Название") },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(depth, { depth = it }, label = { Text("Глубина, м") },
                    modifier = Modifier.weight(1f))
                OutlinedTextField(spacing, { spacing = it }, label = { Text("Шаг, м") },
                    modifier = Modifier.weight(1f))
                OutlinedTextField(mn, { mn = it }, label = { Text("MN, м") },
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val np = Profile(
                    id = System.nanoTime().toString(),
                    name = name.ifBlank { "Профиль" },
                    depthMax = (depth.toDoubleOrNull() ?: 100.0).coerceAtLeast(1.0),
                    offsetPoint = (spacing.toDoubleOrNull() ?: 5.0).coerceAtLeast(0.1),
                    mnSpace = (mn.toDoubleOrNull() ?: 10.0).coerceAtLeast(0.1)
                )
                storage.save(np)
                session.profile.value = np
                onChanged()
            }, modifier = Modifier.fillMaxWidth()) { Text("Начать профиль") }
        } else {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text("Снято точек: ${p.pointCount} · уровней: ${p.levels} · шаг ${p.offsetPoint} м")
            Spacer(Modifier.height(16.dp))

            // Главная кнопка. Её смысл зависит от того, снята ли уже
            // текущая станция: до записи это «Замерить», после — переход
            // на следующую станцию профиля.
            val stationDone = session.lastPoint.value.isNotEmpty() && p.pointCount > 0

            Button(
                onClick = { session.measurePoint() },
                enabled = !session.busy.value && session.isConnected,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    when {
                        session.busy.value -> "Идёт замер… (до 10 минут)"
                        stationDone -> "Следующая точка (${p.pointCount + 1})"
                        else -> "Замерить точку ${p.pointCount + 1}"
                    }
                )
            }
            if (stationDone && !session.busy.value) {
                Text(
                    "Точка ${p.pointCount} записана. Перенесите электроды на " +
                        "${p.offsetPoint} м и нажмите «Следующая точка».",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Прибор сообщил о сбое — показываем это заметно и предлагаем
            // ровно то, что предлагает оригинальное приложение Aidu.
            if (session.measureFailedCode.value != 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Прибор сообщил о сбое замера (код ${session.measureFailedCode.value})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Чаще всего это обрыв цепи MN или слишком большое сопротивление " +
                        "заземления. Проверьте: электроды воткнуты плотно и во влажный " +
                        "грунт, провода целы, клеммы не окислены. Полейте лунки водой. " +
                        "Если уверены в установке — «Принудительно».",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { session.retryPoint() },
                    enabled = !session.busy.value && session.isConnected && p.pointCount > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Повторить") }
                OutlinedButton(
                    onClick = { session.forceRead() },
                    enabled = !session.busy.value && session.isConnected,
                    modifier = Modifier.weight(1f)
                ) { Text("Принудительно") }
            }
            Text(
                "«Повторить» — ещё один замер ТОЙ ЖЕ точки: в профиль пишется " +
                    "медиана всех повторов, а не последнее сырое значение. " +
                    "«Принудительно» — заставить прибор мерить, несмотря на сбой.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                if (p.points.isNotEmpty()) {
                    p.points.removeAt(p.points.size - 1); storage.save(p); onChanged()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Удалить последнюю точку") }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { session.profile.value = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Закрыть профиль")
            }

            if (session.lastPoint.value.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                val q = session.pointQuality.value
                val qColor = when (q) {
                    tj.admt.proc.Physics.Quality.GOOD -> MaterialTheme.colorScheme.primary
                    tj.admt.proc.Physics.Quality.FAIR -> MaterialTheme.colorScheme.tertiary
                    tj.admt.proc.Physics.Quality.POOR -> MaterialTheme.colorScheme.error
                    tj.admt.proc.Physics.Quality.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    "Качество станции: ${tj.admt.proc.Physics.qualityLabel(q)}" +
                        if (q != tj.admt.proc.Physics.Quality.UNKNOWN)
                            " (разброс ${"%.0f".format(session.pointCv.value)}%)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = qColor
                )
                if (q == tj.admt.proc.Physics.Quality.UNKNOWN) {
                    Text(
                        "Один замер станции — разброс не с чем сравнить. " +
                            "Нажмите «Повторить» ещё раз-два для оценки надёжности.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (q == tj.admt.proc.Physics.Quality.POOR) {
                    Text(
                        "Повторы сильно расходятся — вероятна помеха. Переснимите станцию.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Записанные значения (медиана)", style = MaterialTheme.typography.titleSmall)
                session.lastPoint.value.forEachIndexed { i, v ->
                    Text("${"%.1f".format(p.depthOf(i + 1))} м — ${"%.4f".format(v)}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ProfilesTab(
    profiles: List<Profile>,
    session: MeasureSession,
    storage: Storage,
    share: (String, String) -> Unit,
    onChanged: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (profiles.isEmpty()) Text("Профилей пока нет.")
        if (profiles.isNotEmpty()) {
            OutlinedButton(onClick = {
                share("admt-backup.json", Export.toAiduJson(profiles))
            }, modifier = Modifier.fillMaxWidth()) { Text("Резервная копия всех профилей") }
            Spacer(Modifier.height(8.dp))
        }
        profiles.forEach { p ->
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = { Text("${p.pointCount} точек · ${p.depthMax.toInt()} м") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { session.profile.value = p }) { Text("Открыть") }
                        TextButton(onClick = { storage.delete(p); onChanged() }) { Text("Удал.") }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedButton(onClick = { share("${p.name}.csv", Export.toCsv(p)) }) { Text("CSV") }
                OutlinedButton(onClick = { share("${p.name}.xyz", Export.toXyz(p)) }) { Text("XYZ") }
                OutlinedButton(onClick = { share("${p.name}.json", Export.toAiduJson(listOf(p))) }) { Text("JSON") }
            }
            HorizontalDivider()
        }
    }
}

private class PlotBridge {
    @Volatile var xJson: String = "[]"
    @Volatile var dJson: String = "[]"
    @Volatile var zJson: String = "[]"
    @Volatile var logScale: Boolean = false
    @JavascriptInterface fun axisX() = xJson
    @JavascriptInterface fun axisDepth() = dJson
    @JavascriptInterface fun gridZ() = zJson
    @JavascriptInterface fun isLog() = logScale
}

private class VolumeBridge {
    @Volatile var xJson: String = "[]"
    @Volatile var yJson: String = "[]"
    @Volatile var zJson: String = "[]"
    @Volatile var vJson: String = "[]"
    @JavascriptInterface fun volX() = xJson
    @JavascriptInterface fun volY() = yJson
    @JavascriptInterface fun volZ() = zJson
    @JavascriptInterface fun volValue() = vJson
}

@Composable
private fun PlotTab(p: Profile) {
    var smooth by remember { mutableStateOf(3) }
    var log by remember { mutableStateOf(false) }

    val grid = remember(p.pointCount, p.levels, smooth, log, p.points.lastOrNull()?.firstOrNull()) {
        var g = Processing.grid(p)
        g = Processing.smoothByDepth(g, smooth)
        if (log) g = Processing.toLog(g)
        g
    }
    val zones = remember(p.pointCount, p.levels) {
        Processing.lowZones(p, Processing.smoothByDepth(Processing.grid(p), 3))
    }

    val bridge = remember { PlotBridge() }
    bridge.xJson = Processing.axisX(p).toString()
    bridge.dJson = Processing.axisDepth(p).toString()
    bridge.zJson = Processing.gridToJson(grid).toString()
    bridge.logScale = log

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    addJavascriptInterface(bridge, "Bridge")
                    loadUrl("file:///android_asset/contour.html")
                }
            }, update = { it.evaluateJavascript("window.redraw && window.redraw();", null) },
                modifier = Modifier.fillMaxSize())
            DisposableEffect(Unit) {
                onDispose { }
            }
        }

        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сглаживание", Modifier.weight(1f))
                listOf(0, 3, 5).forEach { w ->
                    TextButton(onClick = { smooth = w }) {
                        Text(if (w == 0) "нет" else "$w", color =
                            if (w == smooth) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Логарифм", Modifier.weight(1f))
                Switch(log, { log = it })
            }
            Spacer(Modifier.height(8.dp))
            Text("Зоны пониженных значений", style = MaterialTheme.typography.titleSmall)
            if (zones.isEmpty()) {
                Text(
                    if (p.pointCount == 0)
                        "Пока нет ни одной снятой точки. Перейдите на вкладку «Замер»."
                    else
                        "Данных недостаточно для выделения зон.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            zones.take(5).forEach { a ->
                Text("X ${a.x.toInt()} м · H ${a.depth.toInt()} м · ${"%.3f".format(a.value)} " +
                    "(${(a.ratio * 100).toInt()}% фона)", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            ReportBlock(p)

            Spacer(Modifier.height(8.dp))
            Text(
                "Цвет показывает относительный контраст измеренной величины, не сопротивление " +
                    "в Ом·м и не воду. Шкала глубин условная: паспортный диапазон делится на число уровней. " +
                    "Скин-слой в Physics.kt — оценка, она не подменяет эту шкалу.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VolumeTab(profiles: List<Profile>) {
    var spacing by remember { mutableStateOf("10") }
    val selected = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            Text("Параллельные профили", style = MaterialTheme.typography.titleSmall)
            Text("Отметьте линии в порядке их расположения на местности.",
                style = MaterialTheme.typography.bodySmall)
            profiles.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(p.id), { on ->
                        if (on) selected.add(p.id) else selected.remove(p.id)
                    })
                    Text("${p.name} (${p.pointCount} т.)")
                }
            }
            OutlinedTextField(spacing, { spacing = it },
                label = { Text("Расстояние между линиями, м") })
        }

        val chosen = profiles.filter { selected.contains(it.id) }
        if (chosen.size < 2) {
            EmptyHint("Для трёхмерного вида нужно минимум две линии.")
        } else {
            val vol = remember(chosen.map { it.id }, chosen.sumOf { it.pointCount }, spacing) {
                Processing.buildVolume(chosen, spacing.toDoubleOrNull() ?: 10.0)
            }
            val vBridge = remember { VolumeBridge() }
            vBridge.xJson = with(Processing) { vol.x.toJsonArray().toString() }
            vBridge.yJson = with(Processing) { vol.y.toJsonArray().toString() }
            vBridge.zJson = with(Processing) { vol.z.toJsonArray().toString() }
            vBridge.vJson = with(Processing) { vol.value.toJsonArray().toString() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        addJavascriptInterface(vBridge, "Bridge")
                        loadUrl("file:///android_asset/plot3d.html")
                    }
                }, update = { it.evaluateJavascript("window.redraw && window.redraw();", null) })
            }
        }
    }
}

@Composable
private fun ReportBlock(p: Profile) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("admt", android.content.Context.MODE_PRIVATE) }
    var key by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var question by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val background = remember(p.depthMax) {
        Physics.inferBackgroundResistivity(p.depthMax, Physics.AIDU_FREQUENCIES.min().toDouble())
    }

    Text("Заключение по профилю", style = MaterialTheme.typography.titleSmall)
    Text(
        "Цифры считаются на телефоне. Модель только пишет по ним текст — " +
            "точность замеров она не меняет. Нужны интернет и свой ключ доступа.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(6.dp))

    OutlinedTextField(
        value = key,
        onValueChange = { key = it; prefs.edit().putString("api_key", it).apply() },
        label = { Text("Ключ доступа Anthropic") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = question,
        onValueChange = { question = it },
        label = { Text("Вопрос к данным (необязательно)") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Button(
        onClick = {
            loading = true
            report = ""
            scope.launch {
                val summary = AiReport.buildSummary(p, background)
                val r = AiReport.generate(key, summary, question)
                report = r.text ?: (r.error ?: "Неизвестная ошибка")
                loading = false
            }
        },
        enabled = !loading && p.pointCount > 0,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (loading) "Составляется…" else "Составить заключение")
    }

    if (report.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(report, style = MaterialTheme.typography.bodySmall)
    }
}
