package kz.slam.app.engine

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Движок автозаполнения формы SLAM — перенос SLAM-backend/engine/slam_filler.py.
 *
 * Принципы те же (от них зависит доверие к отчёту):
 *  1. Каждое записанное значение перечитывается из формы: текст сверяется
 *     посимвольно (и со внутренним состоянием React — именно его форма
 *     отправит), выбор — по aria-checked.
 *  2. «Далее» засчитывается, только если форма реально сменила страницу —
 *     MS Forms не пускает дальше при незаполненном обязательном вопросе.
 *  3. Отправлено — ТОЛЬКО при распознанной странице подтверждения, иначе
 *     «не подтверждено».
 *  4. В конце записанное сверяется со сценарием Plan — тем же, что видит бригадир.
 */
class SlamEngine(private val b: FormBrowser, private val budgetMs: Long = 300_000) {

    suspend fun fillOne(data: WorkerData, submit: Boolean): FillResult {
        val res = FillResult()
        val started = now()
        try {
            b.resetSession()
            run(data, submit, res, started + budgetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            res.fail("Исключение: ${e::class.java.simpleName}: ${e.message}")
        } finally {
            res.elapsed = ((now() - started) / 100.0).roundToLong() / 10.0
            runCatching { b.blank() }
        }
        return res
    }

    private suspend fun run(data: WorkerData, submit: Boolean, res: FillResult, deadline: Long) {
        open(res)
        if (!res.ok) return
        page1(data, res)
        if (!res.ok) return
        if (!advance(res, Answers.SECTIONS.getValue("personal"))) return

        // Страницы 2..N: да/нет, последняя — тексты опасностей.
        var reachedEnd = false
        for (i in 0 until MAX_PAGES) {
            if (now() > deadline) {
                res.fail("Превышено время на одного работника — прогон прерван.")
                return
            }
            val st = state()
            val section = st.optString("section")
            if (st.optInt("texts") > 0 && (st.optBoolean("submit") || !st.optBoolean("next"))) {
                controlPage(section, res)
            } else {
                yesNoPage(section, res)
            }
            if (!res.ok) return

            val after = state()
            if (after.optBoolean("submit") && !after.optBoolean("next")) { reachedEnd = true; break }
            if (!after.optBoolean("next")) {
                res.fail("Раздел «${section.take(60)}»: нет ни кнопки «Далее», ни «Отправить».")
                return
            }
            if (!advance(res, section)) return
        }
        if (!reachedEnd) {
            res.fail("Форма не дошла до страницы отправки за $MAX_PAGES переходов.")
            return
        }

        verifyBeforeSubmit(res)
        verifyAgainstPlan(data, res)
        if (!res.ok) return

        if (submit) submit(res)
        else res.log("Все поля заполнены и сверены со сценарием. Кнопка «Отправить» НЕ нажималась (проверка без отправки).")
    }

    // ---------- этапы ----------

    private suspend fun open(res: FillResult) {
        b.load(FORM_URL)
        // Ждём то, что появится раньше: поля или кнопку стартового экрана
        // (он у этой формы есть всегда).
        val ready = poll(30_000) { state().let { it.optInt("texts") > 0 || it.optBoolean("start") } }
        if (!ready) {
            res.fail("Форма не загрузилась: за 30 с не появились ни поля ввода, ни кнопка «Начать». " + snapshot())
            return
        }
        if (state().optInt("texts") == 0) {
            // Кнопка «Начать» рисуется раньше, чем страница «оживает», и
            // нажатие в этот момент просто теряется. Поэтому жмём повторно,
            // пока кнопка на месте, а полей всё нет.
            var clicks = 0
            var lastClick = 0L
            val opened = poll(45_000, 250) {
                val s = state()
                if (s.optInt("texts") > 0) return@poll true
                if (s.optBoolean("start") && now() - lastClick >= START_RETRY_MS) {
                    b.call("clickStart"); clicks++; lastClick = now()
                }
                false
            }
            if (!opened) {
                res.fail("После кнопки «Начать» поля формы не появились за 45 с (нажатий: $clicks). " + snapshot())
                return
            }
            if (clicks > 1) res.log("Кнопку «Начать» пришлось нажать $clicks раза — страница не сразу была готова.")
        }
        settle()
        res.log("Форма открыта, стартовый экран пройден.")
    }

    private suspend fun page1(data: WorkerData, res: FillResult) {
        val section = state().optString("section").ifBlank { Answers.SECTIONS.getValue("personal") }
        val taskText = Answers.WORK_TASK[data.task] ?: run { res.fail("Неизвестный тип задания: ${data.task}"); return }
        val objectText = Answers.OBJECT_OPTIONS[data.objectKey] ?: run { res.fail("Неизвестный объект: ${data.objectKey}"); return }
        val company = data.company.trim()
        if (company.isEmpty()) { res.fail("Не указана подрядная организация."); return }

        val texts = state().optInt("texts")
        if (texts < 3) { res.fail("Стр.1: ожидалось 3 текстовых поля, найдено $texts."); return }
        val values = listOf("fio" to data.fullName, "workplace" to data.workplace, "task" to taskText)
        for ((pos, kv) in values.withIndex()) {
            val (key, value) = kv
            if (!fillText(Answers.MARKERS_PERSONAL.getValue(key), pos, value, section,
                    Answers.Q_PERSONAL.getValue(key), key, res)) return
        }
        res.log("Стр.1: ФИО «${data.fullName}», место «${data.workplace}», задание «$taskText».")

        // Q4: департамент -> Подрядная организация (после этого появляется Q5).
        if (!pick("dept", Answers.DEPARTMENT_CONTRACTOR, section, res)) return
        settle()
        // Q5: объект работ (после этого появляется Q6).
        if (!pick("object", objectText, section, res)) return
        settle()
        // Q6: наименование подрядной организации -> «Другое» + текст.
        company(company, section, res)
    }

    private suspend fun company(company: String, section: String, res: FillResult) {
        val marker = Answers.MARKERS_PERSONAL.getValue("company")
        if (!poll(8_000) { b.call("hasQuestion", marker) == true }) {
            res.fail("Стр.1: не появился вопрос «Наименование подрядной организации» (Q6).")
            return
        }
        val info = b.call("companyInfo", marker) as JSONObject
        val title = info.optString("title").ifBlank { Answers.Q_PERSONAL.getValue("company") }
        if (!info.optBoolean("other")) {
            res.fail("Стр.1: в вопросе «Наименование подрядной организации» не найден вариант «Другое».")
            return
        }
        if (!info.optBoolean("input")) {
            res.fail("Стр.1: не найдено свободное поле для названия организации.")
            return
        }
        b.call("clickOther", marker)
        delay(150)
        if (checkedValue(b.call("otherChecked", marker)) == false) {
            b.call("clickOther", marker)
            delay(150)
        }
        if (!fillText(marker, -1, company, section, title, "company", res, kind = "choice+other")) return

        when (checkedValue(b.call("otherChecked", marker))) {
            false -> res.fail("Стр.1: вариант «Другое» не отметился — название организации не будет учтено формой.")
            null -> res.warn("Стр.1: форма не сообщила состояние варианта «Другое»; проверено переходом «Далее».")
            true -> {}
        }
    }

    private suspend fun yesNoPage(section: String, res: FillResult) {
        val isHazard = Answers.HAZARD_SECTION_MARKERS.any { norm(section).contains(it) }
        val answer = if (isHazard) Answers.NO else Answers.YES
        val questions = b.call("choiceQuestions") as JSONArray
        if (questions.length() == 0) {
            res.fail("Раздел «${section.take(60)}»: не найдено ни одного вопроса да/нет.")
            return
        }
        for (i in 0 until questions.length()) {
            val q = questions.getJSONObject(i)
            pickIn(mapOf("index" to q.getInt("index")), answer, section, q.optString("title"),
                if (isHazard) "no" else "yes", res)
        }
        res.log("Раздел «${section.take(40)}»: ${questions.length()} вопрос(ов) -> «$answer».")
    }

    private suspend fun controlPage(sectionIn: String, res: FillResult) {
        val section = sectionIn.ifBlank { Answers.SECTIONS.getValue("control") }
        val texts = state().optInt("texts")
        if (texts < Answers.CONTROL_FIELDS.size) {
            res.fail("Стр. опасностей: ожидалось ${Answers.CONTROL_FIELDS.size} полей, найдено $texts.")
            return
        }
        for ((pos, f) in Answers.CONTROL_FIELDS.withIndex()) {
            fillText(f.marker, pos, Answers.HAZARDS.getValue(f.key), section, f.question, f.key, res)
        }
        res.log("Раздел «${section.take(40)}»: заполнено ${Answers.CONTROL_FIELDS.size} текстовых поля.")
    }

    /** Нажать «Далее» и убедиться, что форма реально перешла дальше. */
    private suspend fun advance(res: FillResult, section: String): Boolean {
        val before = sig()
        if (b.call("clickNext") != true) {
            res.fail("Раздел «${section.take(60)}»: не найдена кнопка «Далее».")
            return false
        }
        if (poll(20_000) { sig() != before }) {
            settle()
            return true
        }
        val msgs = alerts()
        res.fail("Раздел «${section.take(60)}»: форма не перешла на следующую страницу — " +
            "похоже, остался незаполненный обязательный вопрос." +
            if (msgs.isNotEmpty()) " Сообщение формы: " + msgs.take(3).joinToString(" | ") else "")
        return false
    }

    private suspend fun submit(res: FillResult) {
        b.call("clickSubmit")
        val markers = CONFIRM_MARKERS
        if (poll(40_000, 400) { b.call("confirmed", markers) == true }) {
            res.submitted = true
            res.log("Форма отправлена — получено подтверждение от Microsoft Forms.")
        } else if (state().optBoolean("submit")) {
            val msgs = alerts()
            res.fail("Форма НЕ отправлена: кнопка «Отправить» осталась на экране." +
                if (msgs.isNotEmpty()) " Сообщение формы: " + msgs.take(3).joinToString(" | ") else "")
        } else {
            // Кнопка исчезла, но подтверждение не распознали — честно говорим «не знаем».
            res.unconfirmed = true
            res.warn("Кнопка «Отправить» нажата, страница ушла, но подтверждение не распознано. " +
                "Проверьте ответ вручную — при повторной отправке возможен дубликат.")
            res.log("Отправка не подтверждена — требуется ручная проверка.")
        }
    }

    // ---------- запись значений с перечитыванием ----------

    private suspend fun fillText(marker: String, pos: Int, value: String, section: String,
                                 fallbackTitle: String, key: String, res: FillResult,
                                 kind: String = "text"): Boolean {
        var actual = ""
        var err = ""
        var title = fallbackTitle
        var warned = false
        repeat(3) {
            val r = b.call("fillText", marker, pos, value) as JSONObject
            if (!r.optBoolean("ok")) {
                err = r.optString("error")
                delay(200)
                return@repeat
            }
            if (r.optBoolean("byTitle")) title = r.optString("title").ifBlank { fallbackTitle }
            else if (!warned) {
                warned = true
                res.warn("Вопрос «$fallbackTitle» не найден по заголовку, заполнено ${pos + 1}-е поле по порядку.")
            }
            delay(60)
            val rd = b.call("readText", marker, pos) as JSONObject
            actual = rd.optString("actual")
            val react = if (rd.isNull("react")) null else rd.optString("react")
            if (actual == value && (react == null || react == value)) {
                res.record(section, title, kind, actual, true, key = key)
                return true
            }
            err = if (react != null && react != value) "форма не приняла значение" else ""
            delay(150)
        }
        res.record(section, title, kind, actual, false,
            note = err.ifEmpty { "значение в поле не совпало с ожидаемым" }, key = key)
        res.fail("Поле «${title.take(60)}»: записать не удалось (в поле осталось «${actual.take(40)}»" +
            (if (err.isNotEmpty()) "; $err" else "") + ").")
        return false
    }

    private suspend fun pick(key: String, wanted: String, section: String, res: FillResult): Boolean {
        val marker = Answers.MARKERS_PERSONAL.getValue(key)
        if (!poll(8_000) { b.call("hasQuestion", marker) == true }) {
            res.fail("Стр.1: не найден вопрос «${Answers.Q_PERSONAL.getValue(key)}».")
            return false
        }
        return pickIn(mapOf("marker" to marker), wanted, section, Answers.Q_PERSONAL.getValue(key), key, res)
    }

    private suspend fun pickIn(spec: Map<String, Any>, wanted: String, section: String,
                               questionIn: String, key: String, res: FillResult): Boolean {
        var text = ""
        var question = questionIn
        repeat(2) {
            val r = b.call("pick", spec, wanted) as JSONObject
            if (!r.optBoolean("found")) {
                res.record(section, question, "choice", "", false,
                    note = "вариант «$wanted» отсутствует в форме", key = key)
                res.fail("Вопрос «${question.take(60)}»: не найден вариант «$wanted».")
                return false
            }
            text = r.optString("text")
            question = r.optString("title").ifBlank { question }
            poll(2_500) { checkedValue(b.call("isChecked", spec, wanted)) != false }
            when (checkedValue(b.call("isChecked", spec, wanted))) {
                true -> {
                    res.record(section, question, "choice", text, true, key = key)
                    return true
                }
                null -> {
                    // Форма не сообщает состояние — реальной проверкой станет «Далее».
                    res.record(section, question, "choice", text, false,
                        note = "форма не сообщает состояние выбора; проверено переходом «Далее»", key = key)
                    return true
                }
                false -> delay(200)
            }
        }
        res.record(section, question, "choice", text, false,
            note = "после нажатия вариант остался невыбранным", key = key)
        res.fail("Вопрос «${question.take(60)}»: вариант «${text.take(40)}» не отметился.")
        return false
    }

    // ---------- итоговые проверки ----------

    private suspend fun verifyBeforeSubmit(res: FillResult) {
        val empty = b.call("requiredEmpty") as JSONArray
        if (empty.length() > 0) {
            res.fail("Пустые обязательные поля перед отправкой: " +
                (0 until empty.length()).joinToString("; ") { empty.getString(it) })
        } else {
            res.log("Проверка: обязательные поля финальной страницы заполнены.")
        }
    }

    /** Сверка протокола со сценарием, который видит бригадир. */
    private fun verifyAgainstPlan(data: WorkerData, res: FillResult) {
        val plan = Plan.resolve(data.fullName, data.workplace, data.task, data.objectKey, data.company)
        val written = res.fields.filter { it.kind != "choice" && it.verified }.associate { it.key to it.value }
        val touched = res.fields.map { it.key }.toSet()
        val problems = mutableListOf<String>()
        for (page in plan) for (item in page.items) {
            if (item.kind == "choice") {
                if (item.key !in setOf("yes", "no") && item.key !in touched) problems += "${item.q} — вопрос не заполнялся"
                continue
            }
            val got = written[item.key]
            when {
                got == null -> problems += "${item.q} — не записано"
                got != item.value -> problems += "${item.q} — записано другое значение"
            }
        }
        if (problems.isNotEmpty()) {
            res.fail("Расхождение со сценарием: " + problems.take(6).joinToString("; "))
            return
        }
        val yes = res.fields.count { it.key == "yes" }
        val no = res.fields.count { it.key == "no" }
        if (yes != Answers.EXPECTED_YES || no != Answers.EXPECTED_NO) {
            res.warn("Форма изменилась: ожидалось «Да»×${Answers.EXPECTED_YES} и «Нет»×${Answers.EXPECTED_NO}, " +
                "фактически «Да»×$yes и «Нет»×$no. Все вопросы отвечены по общему правилу — проверьте сценарий.")
        }
        res.log("Сверка со сценарием пройдена: ${written.size} текстовых поля, «Да»×$yes, «Нет»×$no.")
    }

    // ---------- помощники ----------

    private suspend fun state(): JSONObject = b.call("state") as? JSONObject ?: JSONObject()

    /** Что на экране формы — в текст ошибки, чтобы сбой было видно, а не гадать. */
    private suspend fun snapshot(): String = runCatching {
        val s = b.call("snapshot") as JSONObject
        "Состояние страницы: ${s.optString("ready")}, полей ${s.optInt("texts")}, " +
            "кнопка «Начать» ${if (s.optBoolean("start")) "есть" else "нет"}; текст: «${s.optString("body").take(200)}»"
    }.getOrDefault("")
    private suspend fun sig(): String = b.call("sig") as? String ?: ""
    private suspend fun alerts(): List<String> =
        (b.call("alerts") as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    /** Дождаться, пока форма перестанет дорисовывать вопросы (ветвление). */
    private suspend fun settle(quietMs: Long = 300, timeoutMs: Long = 8_000) {
        val deadline = now() + timeoutMs
        var last: String? = null
        var stableSince = -1L
        while (now() < deadline) {
            val s = sig()
            if (s == last && s.length > 1) {
                if (stableSince < 0) stableSince = now()
                else if (now() - stableSince >= quietMs) return
            } else {
                last = s
                stableSince = -1
            }
            delay(80)
        }
    }

    /** Ждать, пока cond() станет истиной; сбой одного опроса — не повод сдаваться. */
    private suspend fun poll(timeoutMs: Long, intervalMs: Long = 120, cond: suspend () -> Boolean): Boolean {
        val deadline = now() + timeoutMs
        while (true) {
            val ok = try { cond() } catch (e: CancellationException) { throw e } catch (e: Throwable) { false }
            if (ok) return true
            if (now() >= deadline) return false
            delay(intervalMs)
        }
    }

    private fun checkedValue(v: Any?): Boolean? = v as? Boolean

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        const val FORM_URL = "https://forms.office.com/pages/responsepage.aspx?" +
            "id=z_7mWGUcvUKsB3AP7auruNSKV8FZLQpGiZMpsAQgRdlUNlVYWFpURlpRODBEMjlLVktRR0RLS1ZLNS4u" +
            "&origin=QRCode&route=shorturl"
        const val MAX_PAGES = 12
        const val START_RETRY_MS = 4_000L

        /** Признаки страницы «ответ отправлен» (форма трёхъязычная). */
        val CONFIRM_MARKERS = listOf(
            "ваш ответ был отправлен", "ваш ответ отправлен", "ответ отправлен",
            "your response was submitted", "response was submitted",
            "жауабыңыз жіберілді", "жауабыңыз тіркелді",
            "отправить ещё один ответ", "отправить еще один ответ",
            "submit another response", "спасибо", "thank you",
        )

        fun norm(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
    }
}
