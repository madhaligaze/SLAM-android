# -*- coding: utf-8 -*-
"""Мини-автоматизация экрана Android через adb + uiautomator (для e2e-тестов).

Нажимает на элементы по тексту, как пользователь. Кириллицу через
`adb shell input text` ввести нельзя — для ввода используйте латиницу.
"""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")


def adb(*args, check=True) -> str:
    r = subprocess.run([ADB, *args], capture_output=True)
    out = r.stdout.decode("utf-8", "replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode('utf-8', 'replace')}")
    return out


def shell(cmd: str) -> str:
    return adb("shell", cmd)


def nodes():
    """Все элементы экрана: (text, desc, bounds-центр, clickable)."""
    for _ in range(3):
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        start = xml.find("<?xml")
        end = xml.rfind("</hierarchy>")
        if start >= 0 and end > start:
            root = ET.fromstring(xml[start:end + len("</hierarchy>")])
            out = []
            for n in root.iter("node"):
                b = re.findall(r"\d+", n.get("bounds", ""))
                if len(b) == 4:
                    x1, y1, x2, y2 = map(int, b)
                    out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""),
                                "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "box": (x1, y1, x2, y2),
                                "cls": n.get("class", ""), "focused": n.get("focused") == "true"})
            return out
        time.sleep(0.5)
    return []


def texts():
    return [n["text"] or n["desc"] for n in nodes() if n["text"] or n["desc"]]


def find(text, exact=False, timeout=10.0):
    deadline = time.time() + timeout
    while True:
        for n in nodes():
            for v in (n["text"], n["desc"]):
                if v and (v == text if exact else text in v):
                    return n
        if time.time() > deadline:
            return None
        time.sleep(0.7)


def has(text, timeout=0.0) -> bool:
    return find(text, timeout=timeout) is not None


def tap(text, exact=False, timeout=10.0):
    n = find(text, exact, timeout)
    if n is None:
        raise AssertionError(f"на экране нет «{text}». Есть: {texts()[:40]}")
    shell(f"input tap {n['x']} {n['y']}")
    time.sleep(0.6)
    return n


def keyboard_shown() -> bool:
    return "mInputShown=true" in shell("dumpsys input_method")


def hide_keyboard():
    if keyboard_shown():
        back()


def scroll_to(text, max_swipes=8):
    """Прокрутить экран вниз, пока не покажется элемент (списки ленивые).
    Клавиатуру сперва убираем: свайп по ней Gboard превращает в набор слов."""
    for _ in range(max_swipes):
        hide_keyboard()
        n = find(text, timeout=0)
        if n is not None:
            return n
        shell("input swipe 540 1100 540 350 300")   # верх экрана: клавиатуры там не бывает
        time.sleep(0.8)
    return find(text, timeout=2)


def tap_scrolled(text):
    n = scroll_to(text)
    if n is None:
        raise AssertionError(f"на экране нет «{text}» даже после прокрутки")
    shell(f"input tap {n['x']} {n['y']}")
    time.sleep(0.6)
    return n


def tap_xy(x, y):
    shell(f"input tap {x} {y}")
    time.sleep(0.5)


def type_text(s: str):
    """Только латиница/цифры/пробелы."""
    shell("input text " + s.replace(" ", "%s"))
    time.sleep(0.4)


def key(name: str):
    shell(f"input keyevent {name}")
    time.sleep(0.5)


def back():
    key("KEYCODE_BACK")


def home():
    key("KEYCODE_HOME")


def screenshot(path: str):
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def logcat_has(marker: str) -> bool:
    return marker in adb("logcat", "-d", "-s", "SLAM:I", "-v", "raw", check=False)


def wait_log(marker: str, timeout=300.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if logcat_has(marker):
            return True
        time.sleep(2)
    return False
