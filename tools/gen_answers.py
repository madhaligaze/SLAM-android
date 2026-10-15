# -*- coding: utf-8 -*-
"""Генерирует app/src/main/java/kz/slam/app/engine/Answers.kt из
SLAM-backend/engine/answers.py.

Тексты ответов (меры контроля и т.п.) — единственный источник правды для
сервера и приложения. Переписывать их руками нельзя: опечатка ушла бы прямо в
форму. Поменяли answers.py — запустите:

    python tools/gen_answers.py
"""
import importlib.util
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT.parent / "SLAM-backend" / "engine" / "answers.py"
OUT = ROOT / "app" / "src" / "main" / "java" / "kz" / "slam" / "app" / "engine" / "Answers.kt"

spec = importlib.util.spec_from_file_location("answers", SRC)
A = importlib.util.module_from_spec(spec)
spec.loader.exec_module(A)


def s(v: str) -> str:
    """Строковый литерал Kotlin."""
    out = v.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$").replace("\n", "\\n")
    return f'"{out}"'


def str_map(d: dict, indent="        ") -> str:
    return "mapOf(\n" + "".join(f"{indent}{s(k)} to {s(v)},\n" for k, v in d.items()) + indent[:-4] + ")"


def str_list(xs, indent="        ") -> str:
    return "listOf(\n" + "".join(f"{indent}{s(x)},\n" for x in xs) + indent[:-4] + ")"


control = "listOf(\n" + "".join(
    f"        ControlField({s(k)}, {s(q)}, {s(m)}),\n" for k, q, m in A.CONTROL_FIELDS) + "    )"

kt = f"""// СГЕНЕРИРОВАНО tools/gen_answers.py из SLAM-backend/engine/answers.py — не править руками.
package kz.slam.app.engine

/** Стандартные ответы формы SLAM (ветка: Подрядная организация -> Сульфидная фабрика). */
object Answers {{
    val WORK_TASK: Map<String, String> = {str_map(A.WORK_TASK)}

    val HAZARDS: Map<String, String> = {str_map(A.HAZARDS)}

    const val DEPARTMENT_CONTRACTOR = {s(A.DEPARTMENT_CONTRACTOR)}

    val OBJECT_OPTIONS: Map<String, String> = {str_map(A.OBJECT_OPTIONS)}

    const val YES = {s(A.YES)}
    const val NO = {s(A.NO)}

    /** Раздел, где на вопрос(ы) нужно отвечать «Нет». */
    val HAZARD_SECTION_MARKERS: List<String> = {str_list(A.HAZARD_SECTION_MARKERS)}

    val SECTIONS: Map<String, String> = {str_map(A.SECTIONS)}

    /** Полные формулировки вопросов — как в самой форме. */
    val Q_PERSONAL: Map<String, String> = {str_map(A.Q_PERSONAL)}

    /** Маркеры для поиска вопроса в DOM (нормализованные подстроки заголовка). */
    val MARKERS_PERSONAL: Map<String, String> = {str_map(A.MARKERS_PERSONAL)}

    val Q_READINESS: List<String> = {str_list(A.Q_READINESS)}

    val Q_HAZARD_ID: List<String> = {str_list(A.Q_HAZARD_ID)}

    val Q_IMPACT: List<String> = {str_list(A.Q_IMPACT)}

    data class ControlField(val key: String, val question: String, val marker: String)

    /** Финальная страница: ключ в HAZARDS, формулировка вопроса, маркер для поиска. */
    val CONTROL_FIELDS: List<ControlField> = {control}

    val EXPECTED_YES = Q_READINESS.size + Q_IMPACT.size
    val EXPECTED_NO = Q_HAZARD_ID.size
}}
"""
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(kt, encoding="utf-8", newline="\n")
print(f"OK: {OUT.relative_to(ROOT)} ({len(A.HAZARDS)} текстов, {len(A.CONTROL_FIELDS)} полей)")
