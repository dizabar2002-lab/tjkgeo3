package tj.admt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Профиль — одна линия наблюдения.
 * cells[точка][уровень] — значение замера.
 * Формат намеренно совместим с JSON приложения Aidu: те же поля
 * name / mn_space / offset_point / cells{row,col,value}, поэтому файлы
 * можно переносить в обе стороны.
 */
data class Profile(
    val id: String,
    var name: String,
    var deviceModel: String = "ADMT-100S",
    var depthMax: Double = 100.0,
    var levels: Int = 20,
    var mnSpace: Double = 10.0,
    var offsetPoint: Double = 5.0,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var remark: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    val points: MutableList<MutableList<Float>> = mutableListOf(),
    /**
     * Разброс между повторами станции, в процентах, параллельно points
     * (pointCv[i] относится к points[i]). 0 значит «повтор не делали» —
     * это НЕ то же самое, что «замер надёжный», см. Physics.Quality.UNKNOWN
     * для честного различения этих двух случаев в интерфейсе.
     */
    val pointCv: MutableList<Float> = mutableListOf()
) {
    val pointCount: Int get() = points.size

    fun addPoint(values: List<Float>, cv: Float = 0f) {
        points.add(values.toMutableList())
        pointCv.add(cv)
    }

    fun replaceLastPoint(values: List<Float>, cv: Float = 0f) {
        if (points.isEmpty()) { addPoint(values, cv); return }
        points[points.lastIndex] = values.toMutableList()
        if (pointCv.size == points.size) pointCv[pointCv.lastIndex] = cv
        else { pointCv.clear(); repeat(points.size - 1) { pointCv.add(0f) }; pointCv.add(cv) }
    }

    /** Глубина уровня с номером index (от 1). Линейная шкала паспортного диапазона. */
    fun depthOf(index: Int): Double {
        val n = levels.coerceAtLeast(1)
        return depthMax * index / n
    }

    fun toJson(): JSONObject {
        val cells = JSONArray()
        points.forEachIndexed { col, values ->
            values.forEachIndexed { row, v ->
                cells.put(
                    JSONObject()
                        .put("row", row + 1)
                        .put("col", col + 1)
                        .put("value", v.toDouble())
                )
            }
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("deviceModel", deviceModel)
            .put("depthMax", depthMax)
            .put("levels", levels)
            .put("mn_space", mnSpace)
            .put("offset_point", offsetPoint)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("remark", remark)
            .put("createdAt", createdAt)
            .put("cells", cells)
            .put("pointCv", JSONArray().also { a -> pointCv.forEach { a.put(it.toDouble()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val p = Profile(
                id = o.optString("id", System.nanoTime().toString()),
                name = o.optString("name", "Без названия"),
                deviceModel = o.optString("deviceModel", "ADMT-100S"),
                depthMax = o.optDouble("depthMax", 100.0),
                levels = o.optInt("levels", 20).coerceAtLeast(1),
                mnSpace = o.optDouble("mn_space", 10.0),
                offsetPoint = o.optDouble("offset_point", 5.0),
                latitude = o.optDouble("latitude", 0.0),
                longitude = o.optDouble("longitude", 0.0),
                remark = o.optString("remark", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
            val cells = o.optJSONArray("cells") ?: return p
            var maxCol = 0
            var maxRow = 0
            for (i in 0 until cells.length()) {
                val c = cells.getJSONObject(i)
                maxCol = maxOf(maxCol, c.optInt("col", 0))
                maxRow = maxOf(maxRow, c.optInt("row", 0))
            }
            if (maxCol <= 0 || maxRow <= 0) return p
            repeat(maxCol) { p.points.add(MutableList(maxRow) { 0f }) }
            for (i in 0 until cells.length()) {
                val c = cells.getJSONObject(i)
                val col = c.optInt("col", 0) - 1
                val row = c.optInt("row", 0) - 1
                if (col in p.points.indices && row in p.points[col].indices) {
                    p.points[col][row] = c.optDouble("value", 0.0).toFloat()
                }
            }
            p.levels = maxRow

            val cvArr = o.optJSONArray("pointCv")
            if (cvArr != null && cvArr.length() == p.points.size) {
                for (i in 0 until cvArr.length()) p.pointCv.add(cvArr.optDouble(i, 0.0).toFloat())
            } else {
                // старый файл без pointCv, либо число не совпало — честно
                // помечаем «повтор не делали», а не подставляем нули молча
                repeat(p.points.size) { p.pointCv.add(0f) }
            }
            return p
        }

        /** Читает файл, выгруженный приложением Aidu (может содержать несколько профилей). */
        fun fromAiduJson(text: String): List<Profile> {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val out = ArrayList<Profile>()
                for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
                return out
            }
            val root = JSONObject(trimmed)
            val arr = root.optJSONArray("data") ?: return listOf(fromJson(root))
            val out = ArrayList<Profile>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            return out
        }
    }
}

class Storage(private val ctx: Context) {

    private val dir: File
        get() = File(ctx.filesDir, "profiles").apply { mkdirs() }

    fun list(): List<Profile> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { Profile.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    /** Атомарная запись: временный файл + rename, чтобы убийство процесса не портило JSON. */
    fun save(p: Profile) {
        val target = File(dir, "${p.id}.json")
        val tmp = File(dir, "${p.id}.json.tmp")
        tmp.writeText(p.toJson().toString())
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun delete(p: Profile) {
        File(dir, "${p.id}.json").delete()
        File(dir, "${p.id}.json.tmp").delete()
    }

    fun load(id: String): Profile? =
        File(dir, "$id.json").takeIf { it.exists() }
            ?.let { runCatching { Profile.fromJson(JSONObject(it.readText())) }.getOrNull() }
}
