package tj.admt.proc

import org.json.JSONArray
import tj.admt.data.Profile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Обработка данных профиля.
 *
 * Все операции обратимы и не трогают исходные значения: сглаживание,
 * логарифмирование и прочее применяются только на этапе отрисовки.
 * Сырые замеры в файле всегда остаются сырыми.
 */
object Processing {

    /** grid[уровень][точка] — так удобнее отдавать в Plotly. */
    fun grid(p: Profile): Array<FloatArray> {
        val levels = p.levels.coerceAtLeast(1)
        val cols = max(p.pointCount, 1)
        return Array(levels) { row ->
            FloatArray(cols) { col ->
                p.points.getOrNull(col)?.getOrNull(row) ?: 0f
            }
        }
    }

    /** Скользящее среднее по глубине. window — нечётное, 0 или 1 отключают сглаживание. */
    fun smoothByDepth(g: Array<FloatArray>, window: Int): Array<FloatArray> {
        if (window < 3) return g
        val half = (window - 1) / 2
        val rows = g.size
        if (rows == 0) return g
        val cols = g[0].size
        return Array(rows) { r ->
            FloatArray(cols) { c ->
                var sum = 0f
                var n = 0
                for (k in (r - half)..(r + half)) {
                    if (k in 0 until rows) { sum += g[k][c]; n++ }
                }
                if (n == 0) 0f else sum / n
            }
        }
    }

    /** Медиана всех значений — устойчивый фон профиля. */
    fun median(g: Array<FloatArray>): Float {
        val flat = g.flatMap { it.toList() }.sorted()
        if (flat.isEmpty()) return 0f
        val m = flat.size / 2
        return if (flat.size % 2 == 1) flat[m] else (flat[m - 1] + flat[m]) / 2f
    }

    /** Десятичный логарифм с защитой от нулей — для сильно контрастных разрезов. */
    fun toLog(g: Array<FloatArray>): Array<FloatArray> {
        val positives = g.flatMap { it.toList() }.filter { it > 0f }
        val floor = if (positives.isEmpty()) 1e-4f else positives.min() / 2f
        return Array(g.size) { r -> FloatArray(g[r].size) { c -> log10(max(g[r][c], floor)) } }
    }

    data class Anomaly(val x: Double, val depth: Double, val value: Float, val ratio: Float)

    /**
     * Зоны пониженных значений — кандидаты на обводнённость.
     * Возвращаются разнесённые в пространстве точки, а не соседние ячейки
     * одного и того же пятна.
     */
    fun lowZones(p: Profile, g: Array<FloatArray>, limit: Int = 8): List<Anomaly> {
        // Без точек анализировать нечего. Сетка в этом случае состоит из
        // нулей-заглушек, медиана равна нулю, и каждая ячейка выглядит как
        // «100% фона» — получался список мнимых аномалий на профиле, где
        // не снято ни одного замера. Пустой список честнее.
        if (p.pointCount == 0) return emptyList()

        val base = median(g)
        // Фон равен нулю только если ВСЕ значения нулевые: реальных данных
        // нет, есть лишь заполнитель. Делить и сравнивать здесь не с чем.
        if (base <= 0f) return emptyList()

        val all = ArrayList<Anomaly>()
        for (r in g.indices) {
            for (c in g[r].indices) {
                // Столбцы за пределами снятых точек — заглушки, не данные.
                if (c >= p.pointCount) continue
                all.add(
                    Anomaly(
                        x = c * p.offsetPoint,
                        depth = p.depthOf(r + 1),
                        value = g[r][c],
                        ratio = g[r][c] / base
                    )
                )
            }
        }
        all.sortBy { it.value }
        val kept = ArrayList<Anomaly>()
        val dx = p.offsetPoint * 2
        val nLev = p.levels.coerceAtLeast(1)
        val dz = p.depthMax / nLev * 3
        for (a in all) {
            if (kept.size >= limit) break
            if (kept.any { abs(it.x - a.x) <= dx && abs(it.depth - a.depth) <= dz }) continue
            kept.add(a)
        }
        return kept
    }

    /**
     * Объединение нескольких профилей в один массив для трёхмерного вида.
     * Профили считаются параллельными и равноудалёнными; расстояние между
     * ними задаётся отдельно, поскольку прибор его не знает.
     */
    data class Volume(
        val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val value: DoubleArray
    )

    fun buildVolume(profiles: List<Profile>, lineSpacing: Double): Volume {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val zs = ArrayList<Double>()
        val vs = ArrayList<Double>()
        profiles.forEachIndexed { li, p ->
            val g = grid(p)
            for (r in g.indices) {
                for (c in g[r].indices) {
                    xs.add(c * p.offsetPoint)
                    ys.add(li * lineSpacing)
                    zs.add(-p.depthOf(r + 1))
                    vs.add(g[r][c].toDouble())
                }
            }
        }
        return Volume(xs.toDoubleArray(), ys.toDoubleArray(), zs.toDoubleArray(), vs.toDoubleArray())
    }

    fun DoubleArray.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

    fun gridToJson(g: Array<FloatArray>): JSONArray {
        val outer = JSONArray()
        for (row in g) {
            val inner = JSONArray()
            for (v in row) inner.put(v.toDouble())
            outer.put(inner)
        }
        return outer
    }

    fun axisX(p: Profile): JSONArray =
        JSONArray().also { a -> for (c in 0 until max(p.pointCount, 1)) a.put(c * p.offsetPoint) }

    fun axisDepth(p: Profile): JSONArray =
        JSONArray().also { a -> for (r in 1..p.levels) a.put(-p.depthOf(r)) }
}

object Export {

    /** Обычная таблица: строки — уровни глубины, столбцы — точки профиля. */
    fun toCsv(p: Profile): String {
        val sb = StringBuilder()
        sb.append("depth_m")
        for (c in 0 until p.pointCount) sb.append(";").append(c * p.offsetPoint)
        sb.append("\n")
        val g = Processing.grid(p)
        for (r in g.indices) {
            sb.append(p.depthOf(r + 1))
            for (v in g[r]) sb.append(";").append(v)
            sb.append("\n")
        }
        return sb.toString()
    }

    /** Формат XYZ для Surfer / Voxler — то же, что делает VoxlerService у Aidu. */
    fun toXyz(p: Profile): String {
        val sb = StringBuilder()
        val g = Processing.grid(p)
        for (r in g.indices) {
            for (c in g[r].indices) {
                sb.append(c * p.offsetPoint).append(" ")
                    .append(-p.depthOf(r + 1)).append(" ")
                    .append(g[r][c]).append("\n")
            }
        }
        return sb.toString()
    }

    /** JSON в формате приложения Aidu — чтобы файл открывался и там. */
    fun toAiduJson(profiles: List<Profile>): String {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        return org.json.JSONObject().put("data", arr).toString(2)
    }
}
