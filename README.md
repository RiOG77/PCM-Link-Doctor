# PCM Link Doctor

Диагностическое Android-приложение для кейса **Samsung Galaxy ↔ проводной Android Auto ↔ Porsche PCM**.

Текущая версия: **0.2.0 Knox-safe**.

## Задача

Поймать разницу между двумя сценариями:

1. телефон подключён к PCM, но Android Auto автоматически не стартует;
2. пользователь вручную включает `Передача изображений / PTP`, после USB re-enumeration появляется `accessory` и Android Auto начинает работать.

Главный принцип проекта: **observe, do not modify**.

## Что изменено после security review

### 1. Больше нет произвольного shell

AIDL принимает только `String[] argv`. `ShellUserService` запускает `ProcessBuilder(argv)` напрямую — без `sh -c`, пайпов, редиректов и command substitution.

`ShellCommandPolicy` разрешает только точные read-only формы:

- `/system/bin/getprop sys.usb.state`
- `/system/bin/getprop sys.usb.config`
- `/system/bin/getprop persist.sys.usb.config`
- `/system/bin/dumpsys usb`
- `/system/bin/ps -A`
- фиксированный read-only `logcat` с буферами `main/system/events/crash` и `-T`.

Любой другой argv отклоняется внутри `ShellUserService`.

### 2. Knox-safe Shizuku

До `bindUserService` вызывается `Shizuku.getUid()`.

Разрешён только backend:

```text
uid=2000
```

Root/любой другой UID блокируется **до запуска Shizuku UserService**.

После bind дополнительно проверяются:

```text
uid=2000
SELinux=u:r:shell:s0
```

Диагностический движок получает не raw `IRemoteShell`, а только `VerifiedShell`.

При перезапуске Shizuku binder identity проверяется заново. Root fallback отсутствует.

### 3. Базовая диагностика работает без Shizuku

Shizuku теперь необязателен.

Основной источник USB-состояния — события Android:

```text
android.hardware.usb.action.USB_STATE
ACTION_USB_ACCESSORY_ATTACHED
ACTION_USB_ACCESSORY_DETACHED
ACTION_BATTERY_CHANGED
```

Если Shizuku пропал или не был запущен, timeline и ручные маркеры продолжают работать.

### 4. Убран polling `dumpsys usb`

`dumpsys usb` больше не вызывается каждые 1.2 секунды.

Тяжёлый snapshot снимается только после события, когда handshake уже изменился, либо по ручной метке:

- stuck handshake;
- accessory attached/stable;
- `AA не запустился`;
- `После PTP заработало`.

Это уменьшает observer effect во время USB handshake.

### 5. Stuck handshake

Таймер запускается только от:

```text
connected=true && configured=true
```

`USB powered=true` больше не считается признаком подключения к автомобилю.

Есть отдельная ручная метка:

```text
Подключил к авто
```

### 6. Точные метрики

Для USB-событий сохраняются одновременно:

- wall-clock;
- `SystemClock.elapsedRealtime()`.

Считаются:

- attach count;
- detach count;
- flapping transitions;
- attach → accessory, ms;
- PTP → accessory, ms;
- длительность accessory-сессий.

### 7. Android Auto

Наличие основного процесса Android Auto не считается доказательством проекции.

В enhanced snapshot отдельно ищется:

```text
com.google.android.projection.gearhead:projection
```

В logcat сохраняются релевантные USB/AOA/gearhead/car-события; общий `projection` без контекста Android Auto игнорируется, чтобы не ловить MediaProjection.

### 8. Samsung context

Диагностика фиксирует:

- screen interactive state;
- keyguard locked state;
- `persist.sys.usb.config` при доступном безопасном Shizuku;
- Type-C / power/data role из event-triggered `dumpsys usb`;
- появление `adb` в USB function set.

Если `adb=true` появляется на проводном USB, в timeline пишется предупреждение об observer effect. Для Shizuku рекомендуется только **Wireless debugging**.

Auto Blocker приложением не переключается и не читается через private settings. При анализе его состояние проверяется отдельно вручную.

## Privacy

- permission `INTERNET` отсутствует;
- backend отсутствует;
- storage permissions отсутствуют;
- экспорт — через Android SAF `ACTION_CREATE_DOCUMENT`;
- `allowBackup=false`;
- `dataExtractionRules` исключают диагностические данные из cloud backup/device transfer;
- перед записью применяется redaction для MAC/BSSID/SSID/IP/email/serial-like данных;
- полный `getprop` не используется.

## Permissions APK

CI проверяет фактический APK через `aapt2 dump permissions`.

Ожидаемые права:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_SPECIAL_USE
android.permission.POST_NOTIFICATIONS
android.permission.WAKE_LOCK
moe.shizuku.manager.permission.API_V23
```

Также AndroidX добавляет package-local `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

CI падает, если появляются `INTERNET`, storage, install-packages, secure-settings или usage-stats permissions.

## Тестирование

Unit-тестами покрыты:

- `uid=0` → отказ;
- `uid=2000` + неправильный SELinux context → отказ;
- `uid=2000` + `u:r:shell:s0` → допуск;
- запрет `sh -c`;
- запрет `dumpsys battery unplug/reset`;
- запрет `logcat -c/-f`;
- запрет произвольного `getprop`;
- запрет неизвестных executable/argv.

Дополнительно `scripts/ci_safety_check.sh` проверяет production-код и manifest на запрещённые команды/permissions.

## Сценарий теста в машине

1. Запустить PCM Link Doctor **до подключения кабеля**.
2. При наличии Shizuku использовать только Wireless debugging. OEM Unlock не нужен и не должен включаться.
3. Нажать `Начать диагностику`.
4. Подключить телефон к Porsche PCM.
5. Нажать `Подключил к авто`.
6. Если Android Auto не стартовал — нажать `AA не запустился`.
7. Стандартным UI Samsung переключить USB в `Передача изображений / PTP`.
8. Если Android Auto запустился — нажать `После PTP заработало`.
9. Остановить диагностику.
10. Экспортировать ZIP через системный диалог Android.

## Build

- JDK 17
- Gradle Wrapper 8.13
- Android Gradle Plugin 8.13.0
- compileSdk 36
- targetSdk 36
- minSdk 29

После фиксации wrapper сборка выполняется только через:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

## Ограничение

Это диагностический prototype. Наличие read-only архитектуры и отсутствие boot/root операций не заменяет независимый code review перед использованием на критичном устройстве. Проект специально не содержит функций автоматического «ремонта» USB/Android Auto.
