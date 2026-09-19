package tj.admt.proc

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Физика метода и контроль качества.
 *
 * Здесь собрано то, чего нет в приложении Aidu для серии S, но что есть
 * в их же более новом поколении (серия H, приложение 7.1.55): привязка
 * глубины к частоте, а не к простому делению диапазона прибора.
 *
 * Список частот взят из RodFreqDepthDefaultConfig приложения AiduWater
 * 7.1.55 — это реальные рабочие частоты приборов Aidu.
 */
object Physics {

    /**
     * Рабочие частоты приборов Aidu, Гц. Идут от высоких к низким:
     * высокая частота — малая глубина, низкая частота — большая.
     */
    val AIDU_FREQUENCIES: List<Int> = listOf(
        4900, 3300, 2500, 2000, 1600, 1400, 1200, 1100, 900, 800,
        750, 700, 658, 616, 580, 550, 520, 493, 470, 448,
        429, 411, 395, 380, 366, 352, 340, 329, 318, 308,
        299, 290, 282, 276, 266, 260, 253, 246, 240, 235,
        229, 224, 219, 214, 210, 205, 200, 197, 193, 189,
        186, 182, 179, 176, 173, 170, 167, 164, 161, 159,
        157, 155, 152, 150, 147, 145, 143, 141, 139, 137,
        135, 133, 131, 130, 128, 126, 125
    )

    /**
     * Толщина скин-слоя в метрах: d = 503 * sqrt(rho / f)
     *
     * rho — удельное сопротивление среды, Ом·м
     * f   — частота, Гц
     *
     * Это стандартная формула магнитотеллурики. Именно она связывает
     * частоту с глубиной исследования.
     */
    fun skinDepth(resistivityOhmM: Double, frequencyHz: Double): Double {
        if (frequencyHz <= 0.0 || resistivityOhmM <= 0.0) return 0.0
        return 503.0 * sqrt(resistivityOhmM / frequencyHz)
    }

    /**
     * Шкала глубин по списку частот при заданном фоновом сопротивлении.
     *
     * ВАЖНО. Сопротивление среды заранее неизвестно — его и ищут. Поэтому
     * шкала получается настолько точной, насколько верна оценка rho.
     * Это ограничение метода, а не расчёта: ошибка в rho вчетверо сдвигает
     * глубины вдвое.
     */
    fun depthScale(frequencies: List<Int>, backgroundResistivity: Double): List<Double> =
        frequencies.map { skinDepth(backgroundResistivity, it.toDouble()) }

    /**
     * Оценка фонового сопротивления по глубине, которую заявляет прибор.
     *
     * Прибор рассчитан на некоторую предельную глубину при самой низкой
     * своей частоте. Отсюда обратным ходом получается то rho, при котором
     * паспортная глубина сходится с физикой.
     */
    fun inferBackgroundResistivity(maxDepthM: Double, lowestFreqHz: Double): Double {
        if (lowestFreqHz <= 0.0) return 0.0
        return (maxDepthM / 503.0).pow(2) * lowestFreqHz
    }

    /**
     * Результат накопления повторных замеров одной точки.
     *
     * value — медиана повторов, устойчива к выбросам
     * spread — медианное абсолютное отклонение, мера разброса
     * cv — коэффициент вариации в процентах: разброс относительно величины
     */
    data class Stacked(
        val value: Float,
        val spread: Float,
        val cv: Float,
        val repeats: Int
    )

    /**
     * Накопление повторных замеров — единственный честный способ
     * поднять точность одноканального МТ-зонда.
     *
     * Естественное поле нестабильно, поэтому один замер содержит
     * случайную составляющую. Три-пять повторов и взятие медианы
     * убирают её, а разброс между повторами прямо показывает,
     * можно ли доверять точке.
     */
    fun stack(repeats: List<Float>): Stacked {
        if (repeats.isEmpty()) return Stacked(0f, 0f, 0f, 0)
        val sorted = repeats.sorted()
        val med = medianOf(sorted)
        val deviations = repeats.map { abs(it - med) }.sorted()
        val mad = medianOf(deviations)
        val cv = if (med != 0f) (mad / abs(med)) * 100f else 0f
        return Stacked(med, mad, cv, repeats.size)
    }

    private fun medianOf(sorted: List<Float>): Float {
        if (sorted.isEmpty()) return 0f
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2f
    }

    /**
     * Свёртка нескольких замеров ОДНОЙ станции в одну точку профиля.
     *
     * repeats — список полных замеров (каждый: значение на каждом уровне
     * глубины). По каждому уровню берётся медиана независимо от остальных.
     *
     * Итоговый cv станции — наихудший (максимальный) разброс среди всех
     * уровней, а не средний: одного плохого уровня достаточно, чтобы не
     * доверять точке целиком, усреднение по уровням спрятало бы это.
     *
     * Замеры с числом уровней, отличным от первого, отбрасываются как
     * несопоставимые — обрыв связи мог урезать кадр.
     */
    fun stackStation(repeats: List<List<Float>>): Pair<List<Float>, Float> {
        if (repeats.isEmpty()) return emptyList<Float>() to 0f
        val levels = repeats.first().size
        val usable = repeats.filter { it.size == levels }
        if (usable.isEmpty() || levels == 0) return repeats.first() to 0f

        val median = ArrayList<Float>(levels)
        var worstCv = 0f
        for (lvl in 0 until levels) {
            val column = usable.map { it[lvl] }
            val s = stack(column)
            median.add(s.value)
            if (s.cv > worstCv) worstCv = s.cv
        }
        return median to worstCv
    }

    /** Оценка качества станции по числу повторов и худшему cv среди уровней. */
    fun stationQuality(repeatCount: Int, worstCv: Float): Quality =
        quality(Stacked(value = 0f, spread = 0f, cv = worstCv, repeats = repeatCount))

    enum class Quality { GOOD, FAIR, POOR, UNKNOWN }

    fun quality(s: Stacked): Quality = when {
        s.repeats < 2 -> Quality.UNKNOWN
        s.cv < 10f -> Quality.GOOD
        s.cv < 25f -> Quality.FAIR
        else -> Quality.POOR
    }

    fun qualityLabel(q: Quality): String = when (q) {
        Quality.GOOD -> "надёжно"
        Quality.FAIR -> "приемлемо"
        Quality.POOR -> "ненадёжно, переснять"
        Quality.UNKNOWN -> "без повторов"
    }

    /**
     * Диапазон эквивалентности.
     *
     * Фундаментальное свойство метода: тонкий хорошо проводящий слой и
     * толстый умеренно проводящий дают почти одинаковый отклик на
     * поверхности. Их произведение (проводимость на мощность) сохраняется,
     * а сами величины — нет.
     *
     * Функция возвращает границы мощности слоя, которые одинаково хорошо
     * объясняют наблюдённое, при разумном разбросе сопротивления.
     * Это не погрешность прибора, а неустранимая неоднозначность задачи.
     */
    data class Equivalence(val thinMeters: Double, val thickMeters: Double)

    fun equivalenceBand(
        apparentConductance: Double,
        resistivityLow: Double = 5.0,
        resistivityHigh: Double = 100.0
    ): Equivalence = Equivalence(
        thinMeters = apparentConductance * resistivityLow,
        thickMeters = apparentConductance * resistivityHigh
    )

    /**
     * Итоговая оценка перспективности аномалии.
     *
     * Складывается из контраста относительно фона, качества самих замеров
     * и того, подтверждается ли аномалия на соседних точках. Одиночная
     * ячейка, не поддержанная соседями, почти всегда шум.
     */
    data class Score(
        val contrast: Float,
        val supportPoints: Int,
        val quality: Quality,
        val verdict: String
    )

    fun scoreAnomaly(
        value: Float,
        background: Float,
        neighboursBelowBackground: Int,
        q: Quality
    ): Score {
        val contrast = if (background > 0f) value / background else 1f
        val verdict = when {
            q == Quality.POOR -> "Данные ненадёжны — переснять точку"
            contrast > 0.7f -> "Контраст слабый, на фоне не выделяется"
            neighboursBelowBackground < 2 -> "Одиночная ячейка, соседями не поддержана — вероятно помеха"
            contrast < 0.3f && neighboursBelowBackground >= 4 -> "Устойчивая протяжённая зона — заслуживает проверки"
            else -> "Умеренная аномалия, нужен повторный профиль"
        }
        return Score(contrast, neighboursBelowBackground, q, verdict)
    }

    /** Сколько соседей вокруг ячейки тоже ниже фона — проверка связности аномалии. */
    fun countSupport(grid: Array<FloatArray>, row: Int, col: Int, background: Float): Int {
        var n = 0
        for (r in (row - 1)..(row + 1)) {
            for (c in (col - 1)..(col + 1)) {
                if (r == row && c == col) continue
                if (r in grid.indices && c in grid[r].indices && grid[r][c] < background) n++
            }
        }
        return n
    }
}
