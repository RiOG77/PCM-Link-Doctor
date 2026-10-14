# PCM Link Doctor — техническое задание 1.1

Целевая реализация: **v0.2.0 Knox-safe**.

## 1. Цель

Диагностировать нестабильный проводной Android Auto в связке Samsung Galaxy → USB → Porsche PCM и доказуемо отделять:

- физическое USB-подключение;
- USB configured;
- MTP/PTP;
- Android Open Accessory / `accessory`;
- признаки активной Android Auto projection;
- повторную USB-инициализацию после ручного PTP.

Приложение только наблюдает. Автоматическое изменение USB, Android Auto, Bluetooth, PCM или системных настроек запрещено.

## 2. Knox / boot-chain ограничения

Запрещены:

- root / `su`;
- Magisk / Sui;
- OEM Unlock;
- bootloader unlock;
- custom recovery/kernel/firmware;
- `fastboot`, `dd`, mount/remount;
- изменение SELinux;
- write в `/system`, `/vendor`, `/sys`, `/proc`;
- системные settings/property write.

Shizuku допускается только в ADB shell mode через Wireless debugging.

## 3. Identity gate Shizuku

### До bind

Перед `Shizuku.bindUserService()` обязательно:

```java
Shizuku.getUid() == 2000
```

При любом другом UID bind запрещён.

### После bind

UserService должен подтвердить одновременно:

```text
uid=2000
SELinux=u:r:shell:s0
```

Только после этого создаётся `VerifiedShell`.

Диагностический код не должен принимать raw `IRemoteShell` как рабочий shell-интерфейс.

При каждом новом `onBinderReceived` backend identity проверяется заново. Потеря Shizuku не останавливает базовую диагностику.

## 4. Shell API

AIDL:

```text
String exec(in String[] argv)
int uid()
String selinuxContext()
```

Строкового `exec("...")` и `sh -c` быть не должно.

`ShellUserService` обязан:

1. проверить UID + SELinux;
2. вызвать `ShellCommandPolicy.requireAllowed(argv)`;
3. выполнить `new ProcessBuilder(argv)`;
4. не использовать shell parser.

## 5. Exact argv allowlist

Разрешены только:

```text
/system/bin/getprop sys.usb.state
/system/bin/getprop sys.usb.config
/system/bin/getprop persist.sys.usb.config
/system/bin/dumpsys usb
/system/bin/ps -A
```

и фиксированный read-only `logcat`:

```text
/system/bin/logcat
-b main
-b system
-b events
-b crash
-v threadtime
-v year
-T <timestamp>
-d
```

Запрещены любые другие executable, flags или arguments.

В частности должны отклоняться:

```text
sh -c
dumpsys battery unplug
dumpsys battery reset
logcat -c
logcat -f
setprop
settings put/delete
pm grant/revoke
mount/umount
reboot
setenforce
```

## 6. Базовый режим без Shizuku

Приложение обязано сохранять полезность без Shizuku.

Основные сигналы:

```text
android.hardware.usb.action.USB_STATE
UsbManager.ACTION_USB_ACCESSORY_ATTACHED
UsbManager.ACTION_USB_ACCESSORY_DETACHED
Intent.ACTION_BATTERY_CHANGED
SCREEN_ON / SCREEN_OFF / USER_PRESENT
```

`USB_STATE` используется как основной тайминг-сигнал и читается через dynamic receiver.

Минимальные extras:

```text
connected
configured
accessory
mtp
ptp
adb
```

Потеря Shizuku пишется в timeline отдельным событием, но базовая сессия продолжается.

## 7. Observer effect

Постоянный polling `dumpsys usb` запрещён.

`dumpsys usb` выполняется только после события или ручной метки:

- stuck handshake;
- accessory stable;
- accessory attached;
- `AA не запустился`;
- `После PTP заработало`.

Если в `USB_STATE` обнаружен `adb=true`, пишется предупреждение, что проводной ADB меняет USB function set. Для Shizuku в тесте используется Wireless debugging.

## 8. Stuck handshake

Таймер начинается только после состояния:

```text
connected=true
configured=true
accessory=false
```

Если через 10 секунд accessory не появился — событие `Возможен зависший handshake`.

`USB powered=true` не запускает handshake timer.

Пользователь может поставить ручную метку `Подключил к авто`.

## 9. Timing

Каждое timeline-событие хранит:

- wall-clock epoch;
- `SystemClock.elapsedRealtime()`.

Метрики:

- attach count;
- detach count;
- flap transitions;
- attach → accessory, ms;
- PTP → accessory, ms;
- accessory session duration, ms.

## 10. Android Auto

Основной процесс `com.google.android.projection.gearhead` сам по себе не считается доказательством проекции.

Enhanced snapshot отдельно ищет:

```text
com.google.android.projection.gearhead:projection
```

Logcat-фильтр оставляет USB/AOA/gearhead/Android Auto/CAR-сигналы. Строки с общим `projection` без AA/car-контекста не сохраняются, чтобы не ловить MediaProjection.

В manifest должен быть `<queries>` для `com.google.android.projection.gearhead`.

## 11. Samsung context

Фиксировать:

- screen interactive;
- keyguard locked;
- `persist.sys.usb.config` при verified Shizuku;
- `sys.usb.state`;
- `sys.usb.config`;
- Type-C power/data roles из event-triggered `dumpsys usb`;
- наличие `adb` в USB functions.

Auto Blocker не переключать и не читать через private settings. Его состояние при необходимости добавляется в анализ вручную.

## 12. Logcat

Буферы:

```text
main
system
events
crash
```

Использовать временной курсор `-T`, не очищать logcat, не писать через `-f`.

Повторяющиеся строки не удалять. Дополнительно вести агрегированную статистику повторов `×N` в `log_repeat_stats.txt`.

## 13. Privacy

До записи в диагностические файлы применяется redaction для:

- MAC;
- BSSID;
- quoted SSID;
- IPv4;
- email;
- serial-like values.

Полный `getprop` запрещён.

Приложение не объявляет `INTERNET`.

Экспорт только через SAF `ACTION_CREATE_DOCUMENT`.

В manifest:

```text
allowBackup=false
dataExtractionRules=<exclude diagnostics>
usesCleartextTraffic=false
```

## 14. Foreground Service

Для targetSdk 36:

```text
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS
WAKE_LOCK
```

Service:

```text
foregroundServiceType="specialUse"
```

с явно описанным subtype.

Partial wakelock используется только на время пользовательской диагностической сессии.

## 15. Export

ZIP формируется локально и сохраняется пользователем через SAF.

Ожидаемые файлы:

```text
device.txt
timeline.txt
raw_usb.txt
usb_props.txt
snapshots.txt
logcat_filtered.txt
log_repeat_stats.txt
metrics.txt
shell_identity.txt (если enhanced mode был доступен)
```

`raw_usb.txt` пишется только при изменении fingerprint USB-состояния.

## 16. Build

```text
JDK 17
Gradle Wrapper 8.13
Android Gradle Plugin 8.13.0
compileSdk 36
targetSdk 36
minSdk 29
```

Сборка и тесты CI только через committed wrapper:

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

## 17. CI security gates

CI обязан падать при обнаружении:

- `sh -c` / arbitrary shell;
- mutating command literals;
- `INTERNET`;
- storage permissions;
- `REQUEST_INSTALL_PACKAGES`;
- `WRITE_SECURE_SETTINGS`;
- `PACKAGE_USAGE_STATS`;
- accessibility/VPN components.

После сборки запускается:

```text
aapt2 dump permissions <apk>
```

и проверяется фактический merged APK, а не только исходный manifest.

## 18. Unit tests

Минимальный обязательный набор:

- fake `IRemoteShell`, uid=0 → `VerifiedShell.verify()` отказ;
- uid=2000 + wrong SELinux → отказ;
- uid=2000 + `u:r:shell:s0` → допуск;
- `sh -c` → отказ;
- `dumpsys battery unplug/reset` → отказ;
- `logcat -c/-f` → отказ;
- unknown getprop → отказ;
- неизвестный executable → отказ;
- разрешённые argv → допуск.

## 19. A/B observer-effect acceptance

Перед признанием диагностики готовой к регулярному использованию провести на одном телефоне/авто минимум два набора повторов:

**A:** только базовый событийный режим, без Shizuku enhanced snapshot.

**B:** событийный режим + verified Shizuku, event-triggered snapshots/logcat.

Сравнить:

- долю успешных автоматических handshake;
- attach→accessory;
- число attach/detach;
- частоту необходимости PTP.

Если режим B статистически/повторяемо меняет поведение handshake относительно A, enhanced mode считать влияющим на объект диагностики и выключить по умолчанию.

## 20. Acceptance Criteria

Релиз допускается к тесту на Samsung только если:

1. root/не-shell Shizuku блокируется до `bindUserService`;
2. UserService дополнительно проверяет uid=2000 + `u:r:shell:s0`;
3. arbitrary shell отсутствует;
4. argv allowlist покрыт unit-тестами;
5. USB primary path event-driven;
6. `dumpsys usb` не поллится во время handshake;
7. stuck timer стартует от connected+configured;
8. базовый режим работает без Shizuku;
9. `INTERNET` и storage permissions отсутствуют в собранном APK;
10. экспорт идёт через SAF;
11. backup диагностических данных запрещён;
12. privacy redaction применяется до записи;
13. CI safety policy, unit tests, APK build и permission check зелёные;
14. Gradle Wrapper закоммичен и CI использует `./gradlew`;
15. проведён отдельный A/B тест observer effect перед выводами по автомобилю.

## 21. Что отдать на повторный review

- `README.md`;
- этот `TECH_SPEC.md`;
- `ShellCommandPolicy.java`;
- `ShellIdentityPolicy.java`;
- `VerifiedShell.java`;
- `ShellUserService.java`;
- `DiagnosticService.java`;
- `AndroidManifest.xml`;
- unit tests;
- `scripts/ci_safety_check.sh`;
- `.github/workflows/build-apk.yml`;
- APK и SHA-256.
