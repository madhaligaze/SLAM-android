# -*- coding: utf-8 -*-
"""Сквозной тест приложения на эмуляторе против локального сервера.

Нажимает на экран как пользователь (tools/ui.py) и проверяет сервер напрямую.
ВНИМАНИЕ: делает РЕАЛЬНЫЕ отправки формы (ФИО начинаются с «ТЕСТ»).

  1) запустить сервер:  DATABASE_URL=sqlite:///C:/.../e2e.db python -m uvicorn main:app --port 8780  (в SLAM-backend)
  2) python tools/e2e_android.py [--only имя_сценария]
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
import ui  # noqa: E402

sys.stdout.reconfigure(encoding="utf-8")
HOST_API = os.environ.get("API", "http://127.0.0.1:8780") + "/api"
PHONE_SERVER = os.environ.get("PHONE_SERVER", "http://10.0.2.2:8780")
APK = os.path.join(os.path.dirname(__file__), "..", "app", "build", "outputs", "apk", "debug", "app-debug.apk")
SHOTS = os.environ.get("SHOTS", os.path.join(os.path.dirname(__file__), "..", "build", "e2e-shots"))
os.makedirs(SHOTS, exist_ok=True)
fails = 0


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(HOST_API + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "null")


def check(name, cond, extra=""):
    global fails
    print(("PASS " if cond else "FAIL ") + name + (f"  [{extra}]" if extra and not cond else ""), flush=True)
    fails += 0 if cond else 1


def block(bid):
    return [b for b in call("GET", "/blocks")[1] if b["id"] == bid][0]


def wait(cond, timeout=20.0, step=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if cond():
                return True
        except Exception:
            pass
        time.sleep(step)
    return False


def sync_in_app():
    ui.tap("Меню")
    ui.tap("Синхронизировать")
    time.sleep(2)


def start_app(clear=True):
    if clear:
        ui.adb("shell", "pm", "clear", "kz.slam.app", check=False)
        ui.shell("pm grant kz.slam.app android.permission.POST_NOTIFICATIONS")
    ui.key("KEYCODE_WAKEUP"); ui.shell("wm dismiss-keyguard")
    ui.adb("logcat", "-c")
    ui.shell(f"am start -n kz.slam.app/.MainActivity --es server {PHONE_SERVER}")
    time.sleep(3)


def scenario_sync():
    st, a = call("POST", "/blocks", {"name": "ТЕСТ Бригада А", "company": "ТЕСТ"})
    call("PUT", f"/blocks/{a['id']}/workers", {"names": ["ТЕСТ Приложение UI 1", "ТЕСТ Приложение UI 2"]})
    start_app()
    check("server brigade appears in the app", ui.find("ТЕСТ Бригада А", timeout=20) is not None, ui.texts()[:20])
    check("…with its composition count", ui.has("2 работника", timeout=5))
    ui.screenshot(os.path.join(SHOTS, "01-list.png"))

    # правка на сервере (другой бригадир) приходит в приложение
    call("PATCH", f"/blocks/{a['id']}", {"name": "ТЕСТ Бригада А (сервер)"})
    sync_in_app()
    check("rename made elsewhere shows up", ui.find("ТЕСТ Бригада А (сервер)", timeout=15) is not None)

    # создание бригады в приложении уходит на сервер
    ui.tap("+ Бригада")
    ui.key("KEYCODE_MOVE_END"); [ui.key("KEYCODE_DEL") for _ in range(7)]
    ui.type_text("QA brigade")
    ui.tap("Создать")
    ui.tap("+ Добавить работника")
    ui.type_text("Test Worker One")
    ui.key("KEYCODE_ENTER")        # Enter = следующая строка
    time.sleep(1.2)                # новая строка появляется не мгновенно — как и человек, печатаем чуть погодя
    ui.type_text("Test Worker Two")
    ui.back()                      # закрыть клавиатуру
    ui.screenshot(os.path.join(SHOTS, "02-new-brigade.png"))
    ui.tap("Назад")                # сразу назад — правка не должна потеряться
    ok = wait(lambda: any(b["name"] == "QA brigade" and [w["full_name"] for w in b["workers"]] ==
                          ["Test Worker One", "Test Worker Two"] for b in call("GET", "/blocks")[1]), 20)
    check("brigade created in the app reached the server with both names", ok,
          [(b["name"], [w["full_name"] for w in b["workers"]]) for b in call("GET", "/blocks")[1]])
    check("list shows it immediately", ui.has("QA brigade", timeout=5) and ui.has("2 работника"))

    # правка состава существующей бригады и сразу «Назад»
    ui.tap("QA brigade")
    n = ui.find("Test Worker Two")
    ui.tap_xy(n["box"][2] - 20, n["y"]); ui.key("KEYCODE_MOVE_END")
    ui.type_text(" Edited")
    ui.tap("Назад")
    ui.tap("QA brigade")
    check("edited name shown right after Back+reopen", ui.has("Test Worker Two Edited", timeout=5))
    qa = [b for b in call("GET", "/blocks")[1] if b["name"] == "QA brigade"][0]
    check("…and on the server", wait(lambda: block(qa["id"])["workers"][1]["full_name"] == "Test Worker Two Edited", 15))
    ui.tap("Назад")
    return a["id"], qa["id"]


def scenario_submit_background(bid):
    """Настоящая отправка из интерфейса, приложение сразу свернули."""
    ui.adb("logcat", "-c")
    ui.tap("ТЕСТ Бригада А (сервер)")
    wp = ui.find("Где именно проводятся работы")
    ui.tap_xy(wp["x"], wp["y"])
    ui.type_text("TEST app development")
    ui.back()
    ui.tap_scrolled("Отправить за всю бригаду")
    check("confirm lists every name", ui.has("1. ТЕСТ Приложение UI 1", timeout=5) and ui.has("2. ТЕСТ Приложение UI 2"))
    ui.screenshot(os.path.join(SHOTS, "03-confirm.png"))
    ui.tap("Отправить (2)")
    check("job started", ui.wait_log("JOB_START", 30))
    ui.home(); ui.key("KEYCODE_SLEEP")
    on_server = wait(lambda: (call("GET", f"/blocks/{bid}/job")[1] or {}).get("source") == "phone", 30)
    check("other devices see the phone job while it runs", on_server, call("GET", f"/blocks/{bid}/job")[1])
    check("job finished in background", ui.wait_log("JOB_DONE", 300))
    j = call("GET", f"/blocks/{bid}/job")[1]
    check("server: done, 2 of 2 sent", j["status"] == "done" and sum(r["submitted"] for r in j["results"]) == 2,
          (j["status"], [(r["full_name"], r["status"], r["submitted"]) for r in j["results"]]))
    st, f = call("GET", f"/jobs/{j['id']}/fields/0")
    check("protocol of fields uploaded", st == 200 and len(f["fields"]) == 24, (st, f if st != 200 else len(f["fields"])))
    lj = block(bid)["last_job"]
    check("web list summary: sent 2", lj and lj["sent"] == 2 and lj["status"] == "done", lj)
    ui.key("KEYCODE_WAKEUP"); ui.shell("wm dismiss-keyguard")
    ui.shell("am start -n kz.slam.app/.MainActivity")
    check("app shows the result", ui.scroll_to("Готово: отправлено 2 из 2") is not None, ui.texts()[:30])
    ui.screenshot(os.path.join(SHOTS, "04-done.png"))
    ui.tap("Назад")


def scenario_busy_elsewhere(bid):
    """Бригада уже отправляется с другого телефона — второй раз сервер не даст."""
    other = "othr" + str(int(time.time()))
    st, _ = call("POST", f"/blocks/{bid}/phone-jobs", {"id": other, "names": ["ТЕСТ X"], "base": {"device": "other"}})
    check("another device registered a job", st == 200, st)
    ui.adb("logcat", "-c")
    sync_in_app()
    check("list shows it is being sent from another device", ui.has("другое устройство", timeout=10))
    ui.tap("ТЕСТ Бригада А (сервер)")
    ui.tap_scrolled("Отправить за всю бригаду")
    check("confirm warns about the other device", ui.has("отправляется с другого устройства", timeout=5))
    ui.tap("Отправить (2)")
    check("job refused", ui.wait_log("JOB_DONE", 60))
    check("app explains why", ui.scroll_to("уже идёт отправка с другого устройства") is not None, ui.texts()[:30])
    ui.screenshot(os.path.join(SHOTS, "05-busy.png"))
    call("PUT", f"/phone-jobs/{other}", {"status": "done", "done": 1, "results": [
        {"full_name": "ТЕСТ X", "status": "ok", "submitted": True}]})
    ui.tap("Назад")


def scenario_server_down(qa_id, server_proc_stop, server_proc_start):
    """Сервер недоступен: правки и отправка работают, отметки досылаются потом."""
    server_proc_stop()
    ui.adb("logcat", "-c")
    sync_in_app()
    check("app says there is no connection", ui.has("Нет связи с сервером", timeout=30))
    ui.tap("QA brigade")
    ui.tap("+ Добавить работника")
    ui.type_text("Offline Worker")
    ui.back(); ui.tap("Назад")
    check("brigade marked as not yet on the server", ui.has("ещё не на сервере", timeout=10))
    server_proc_start()
    sync_in_app()
    ok = wait(lambda: [w["full_name"] for w in block(qa_id)["workers"]][-1] == "Offline Worker", 20)
    check("offline edit reached the server once it was back", ok, block(qa_id)["workers"])
    check("«not on the server» mark gone", not ui.has("ещё не на сервере", timeout=3))


SCENARIOS = ["submit", "busy", "offline"]


class Backend:
    """Локальный сервер (тот же main.py, что на Railway) на чистой SQLite-базе."""

    def __init__(self):
        self.py = os.environ.get("BACKEND_PY", sys.executable)
        self.dir = os.environ.get("BACKEND_DIR", os.path.join(os.path.dirname(__file__), "..", "..", "SLAM-backend"))
        self.db = os.environ.get("BACKEND_DB", os.path.abspath(os.path.join(SHOTS, "e2e.db"))).replace("\\", "/")
        self.p = None

    def start(self):
        env = {**os.environ, "DATABASE_URL": "sqlite:///" + self.db, "PYTHONIOENCODING": "utf-8"}
        self.p = subprocess.Popen([self.py, "-m", "uvicorn", "main:app", "--port", "8780", "--log-level", "warning"],
                                  cwd=self.dir, env=env)
        if not wait(lambda: call("GET", "/blocks")[0] == 200, 40, 0.5):
            raise SystemExit("backend did not start")

    def stop(self):
        if self.p:
            self.p.kill(); self.p.wait(); self.p = None
        time.sleep(1)


if __name__ == "__main__":
    only = sys.argv[sys.argv.index("--only") + 1].split(",") if "--only" in sys.argv else SCENARIOS
    be = Backend()
    if os.path.exists(be.db):
        os.remove(be.db)
    be.start()
    try:
        ui.adb("install", "-r", APK)
        a_id, qa_id = scenario_sync()
        if "submit" in only:
            scenario_submit_background(a_id)
        if "busy" in only:
            scenario_busy_elsewhere(a_id)
        if "offline" in only:
            scenario_server_down(qa_id, be.stop, be.start)
    finally:
        be.stop()
    print("FAILS", fails)
