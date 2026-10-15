#!/usr/bin/env bash
# Прогон движка на эмуляторе/телефоне через adb.
#   tools/engine_test.sh [--install] [--submit] [--bg] "ТЕСТ Имя 1|ТЕСТ Имя 2"
# --bg     сразу после старта свернуть приложение и погасить экран
#          (как бригадир, убравший телефон в карман)
# --submit РЕАЛЬНАЯ отправка формы (тестовые ФИО начинать с «ТЕСТ»)
set -u
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
APK="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"
install=0; submit=false; bg=0; names="ТЕСТ Приложение 1"
for a in "$@"; do
  case "$a" in
    --install) install=1 ;;
    --submit) submit=true ;;
    --bg) bg=1 ;;
    *) names="$a" ;;
  esac
done
b64() { printf '%s' "$1" | base64 -w0; }

if [ $install = 1 ]; then
  "$ADB" install -r "$APK" >/dev/null && echo "installed"
  "$ADB" shell pm grant kz.slam.app android.permission.POST_NOTIFICATIONS 2>/dev/null
fi
"$ADB" shell input keyevent KEYCODE_WAKEUP; "$ADB" shell wm dismiss-keyguard 2>/dev/null
"$ADB" logcat -c
"$ADB" shell am start -n kz.slam.app/.MainActivity --ez autostart true --ez submit $submit \
  --es names64 "$(b64 "$names")" --es workplace64 "$(b64 "ТЕСТ — разработка приложения")" \
  --es company64 "$(b64 "ТЕСТ")" --es task "${TASK:-montazh}" --es object "${OBJECT:-sulphide_1}" --es brigade64 "$(b64 "ТЕСТ")" >/dev/null
t0=$SECONDS
if [ $bg = 1 ]; then
  # сворачиваем только когда отправка реально началась (служба запущена из открытого окна)
  for _ in $(seq 1 40); do "$ADB" logcat -d -s SLAM:I -v raw | grep -q JOB_START && break; sleep 0.5; done
  sleep 1
  "$ADB" shell input keyevent KEYCODE_HOME
  "$ADB" shell input keyevent KEYCODE_SLEEP
  echo "app sent to background, screen off"
fi
while [ $((SECONDS - t0)) -lt 600 ]; do
  if "$ADB" logcat -d -s SLAM:I -v raw | grep -q JOB_DONE; then break; fi
  sleep 3
done
echo "elapsed ${SECONDS}s since start: $((SECONDS - t0))s"
# Лог читаем байтами и декодируем как UTF-8 сами: конвейер Git Bash портит кириллицу.
"$ADB" logcat -d -s SLAM:I -v raw | python -c "
import sys, json
sys.stdout.reconfigure(encoding='utf-8')
for line in sys.stdin.buffer.read().decode('utf-8', 'replace').splitlines():
    if line.startswith('RESULT '):
        r = json.loads(line[7:])
        print(f\"[{r['i']}] {r['name']}: {r['status']} submitted={r['submitted']} {r['elapsed']}s fields {r['verified']}/{r['fields']}\")
        for k in ('errors', 'warnings', 'steps'):
            for x in r[k]: print('  ', k[:-1], ':', x)
    elif 'JOB_DONE' in line or 'Exception' in line: print(line)
"
