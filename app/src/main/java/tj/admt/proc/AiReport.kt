package tj.admt.proc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tj.admt.data.Profile
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Составление заключения по профилю с помощью языковой модели Claude.
 *
 * Что здесь происходит и чего не происходит.
 *
 * Модель НЕ обрабатывает сигнал и НЕ повышает точность замеров. Вся
 * числовая работа — сетка, сглаживание, накопление повторов, контроль
 * качества, поиск связных аномалий — делается на телефоне, в модулях
 * Processing и Physics, и делается детерминированно.
 *
 * Модель получает уже готовые цифры и пишет по ним текст: связное
 * заключение для заказчика, с оговорками об ограничениях метода.
 * Это работа с языком, а не с геофизикой.
 *
 * Требуется интернет и собственный ключ доступа, который вводится
 * в настройках приложения. Каждый запрос платный по тарифам Anthropic.
 */
object AiReport {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-sonnet-5"
    private const val API_VERSION = "2023-06-01"

    /**
     * Готовит компактную числовую выжимку профиля.
     * Отправляются только цифры и геометрия — ни координат, ни имён заказчиков.
     */
    fun buildSummary(p: Profile, backgroundResistivity: Double): String {
        val g = Processing.smoothByDepth(Processing.grid(p), 3)
        val base = Processing.median(g)
        val zones = Processing.lowZones(p, g, 6)

        val sb = StringBuilder()
        sb.append("Прибор: ${p.deviceModel}, одноканальный магнитотеллурический зонд\n")
        sb.append("Профиль: ${p.pointCount} точек, шаг ${p.offsetPoint} м, разнос MN ${p.mnSpace} м\n")
        sb.append("Уровней по глубине: ${p.levels}, паспортная глубина ${p.depthMax} м\n")
        sb.append("Оценка фонового сопротивления: %.1f Ом·м\n".format(backgroundResistivity))
        sb.append("Медианный фон по профилю: %.4f\n\n".format(base))

        sb.append("Зоны пониженных значений:\n")
        zones.forEach { z ->
            val row = ((z.depth / p.depthMax) * p.levels).toInt().coerceIn(1, p.levels) - 1
            val col = (z.x / p.offsetPoint).toInt().coerceIn(0, maxOf(p.pointCount - 1, 0))
            val support = Physics.countSupport(g, row, col, base)
            val score = Physics.scoreAnomaly(z.value, base, support, Physics.Quality.UNKNOWN)
            sb.append(
                "- X %.0f м, H %.0f м, значение %.4f (%.0f%% фона), соседей ниже фона: %d — %s\n"
                    .format(z.x, z.depth, z.value, z.ratio * 100, support, score.verdict)
            )
        }

        sb.append("\nЗначения по уровням (медиана каждого уровня):\n")
        for (r in g.indices) {
            val rowMedian = g[r].sorted()[g[r].size / 2]
            sb.append("  %.0f м: %.4f\n".format(p.depthOf(r + 1), rowMedian))
        }
        return sb.toString()
    }

    private val SYSTEM_PROMPT = """
        Ты помогаешь инженеру-геофизику составить заключение по данным
        одноканального магнитотеллурического зондирования прибором серии ADMT.

        Строгие правила:
        - Измеренная величина НЕ является удельным сопротивлением в Ом·м.
          Это относительный отклик. Не переводи её в Ом·м и не называй так.
        - Шкала глубин приблизительна: она получена через скин-слой при
          оценочном фоновом сопротивлении. Ошибка в фоне вчетверо смещает
          глубины вдвое. Всегда указывай глубины как оценочные.
        - Метод неоднозначен: тонкий хорошо проводящий слой и толстый
          умеренно проводящий дают близкий отклик. Не выдавай мощность
          слоя за установленную величину.
        - Низкие значения могут быть вызваны септиками, влажным грунтом
          у построек, трубами, корнями деревьев, а не водоносным горизонтом.
          Всегда предлагай проверить окружение аномалии.
        - Не называй вероятность успеха в процентах. Её нет.
        - Не рекомендуй бурение как установленный факт. Формулируй как
          участок, заслуживающий проверки, и указывай, чем проверить.

        Пиши по-русски, сухо и по делу, без рекламных оборотов.
        Структура: что измерено, что видно на разрезе, какие участки
        выделяются, чем объясняются, что делать дальше.
        Объём — до 400 слов.
    """.trimIndent()

    data class Result(val text: String?, val error: String?)

    suspend fun generate(apiKey: String, summary: String, question: String = ""): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result(null, "Не введён ключ доступа")

            val userText = buildString {
                append("Данные профиля:\n\n")
                append(summary)
                if (question.isNotBlank()) {
                    append("\n\nДополнительный вопрос инженера: ")
                    append(question)
                }
            }

            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 1500)
                .put("system", SYSTEM_PROMPT)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", userText)
                    )
                )

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 90000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", API_VERSION)
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body.toString())
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

                if (code !in 200..299) {
                    val msg = runCatching {
                        JSONObject(text).getJSONObject("error").getString("message")
                    }.getOrElse { text.take(300) }
                    return@withContext Result(null, "Сервис ответил $code: $msg")
                }

                val content = JSONObject(text).optJSONArray("content")
                val out = StringBuilder()
                if (content != null) {
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") == "text") out.append(block.optString("text"))
                    }
                }
                Result(out.toString().ifBlank { "Пустой ответ" }, null)
            } catch (e: Exception) {
                Result(null, "Нет связи с сервисом: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
}
