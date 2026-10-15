package kz.slam.app.engine

/** Сценарий заполнения: что именно будет записано в каждое поле. */
data class PlanItem(
    val key: String,
    val q: String,
    val kind: String,
    val value: String,
    val source: String,        // worker | session | block | constant
    val note: String = "",
)

data class PlanPage(val section: String, val items: List<PlanItem>)

/**
 * Перенос build_plan/resolve_plan из answers.py. Один и тот же сценарий
 * показывается бригадиру и сверяется с фактически записанным — разойтись
 * «показали одно, записали другое» они не могут.
 */
object Plan {
    fun build(fullName: String, workplace: String, taskText: String,
              objectText: String, company: String): List<PlanPage> {
        val a = Answers
        return listOf(
            PlanPage(a.SECTIONS.getValue("personal"), listOf(
                PlanItem("fio", a.Q_PERSONAL.getValue("fio"), "text", fullName, "worker"),
                PlanItem("workplace", a.Q_PERSONAL.getValue("workplace"), "text", workplace, "session"),
                PlanItem("task", a.Q_PERSONAL.getValue("task"), "text", taskText, "block"),
                PlanItem("dept", a.Q_PERSONAL.getValue("dept"), "choice", a.DEPARTMENT_CONTRACTOR, "constant",
                    "всегда «Подрядная организация»"),
                PlanItem("object", a.Q_PERSONAL.getValue("object"), "choice", objectText, "block"),
                PlanItem("company", a.Q_PERSONAL.getValue("company"), "choice+other", company, "block",
                    "выбирается «Другое» и вписывается название"),
            )),
            PlanPage(a.SECTIONS.getValue("readiness"),
                a.Q_READINESS.map { PlanItem("yes", it, "choice", a.YES, "constant") }),
            PlanPage(a.SECTIONS.getValue("hazard_id"),
                a.Q_HAZARD_ID.map { PlanItem("no", it, "choice", a.NO, "constant", "единственный вопрос, где ответ «Нет»") }),
            PlanPage(a.SECTIONS.getValue("impact"),
                a.Q_IMPACT.map { PlanItem("yes", it, "choice", a.YES, "constant") }),
            PlanPage(a.SECTIONS.getValue("control"),
                a.CONTROL_FIELDS.map { PlanItem(it.key, it.question, "text", a.HAZARDS.getValue(it.key), "constant") }),
        )
    }

    fun resolve(fullName: String, workplace: String, task: String,
                objectKey: String, company: String): List<PlanPage> = build(
        fullName, workplace,
        Answers.WORK_TASK[task] ?: "<неизвестный тип работ: $task>",
        Answers.OBJECT_OPTIONS[objectKey] ?: "<неизвестный объект: $objectKey>",
        company,
    )
}
