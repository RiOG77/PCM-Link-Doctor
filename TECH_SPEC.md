# PCM Link Doctor — техническое задание 1.2

Целевая реализация: **v0.2.1 Knox-safe**.

## 1. Цель

Диагностировать нестабильный проводной Android Auto в связке Samsung Galaxy → USB → Porsche PCM и отделять:

- физическое USB-подключение;
- `configured`;
- MTP/PTP;
- Android Open Accessory / `accessory`;
- повторную USB-инициализацию;
- влияние ADB/Shizuku на наблюдаемый кейс.

Приложение только наблюдает. Автоматическое изменение USB, Android Auto, Bluetooth, PCM или системных настроек запрещено.

## 2. Подтверждённый полевой паттерн

На двух чистых сессиях без Shizuku и `adb` подтверждено:

```text
connected=true
configured=true
accessory=false длительное время
→ ручной PTP
→ configured drop/re-enumeration
→ accessory=true через ~2.1–2.2 s
→ Android Auto работает
```

v0.2.1 должна собирать максимум данных именно вокруг этого перехода без включения USB debugging.

## 3. Knox / boot-chain ограничения

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

## 4. Clean base mode

Base mode — режим по умолчанию.

При обычном запуске:

- Shizuku не bind-ится;
- Shizuku permission не запрашивается;
- shell-команды не выполняются;
- `dumpsys` не выполняется;
- logcat не читается;
- USB mode не меняется.

Enhanced mode включается только отдельным пользовательским действием и не используется для чистого Porsche-теста.

## 5. Базовые USB-сигналы

Основной сигнал:

```text
android.hardware.usb.action.USB_STATE
```

Также слушать read-only:

```text
UsbManager.ACTION_USB_ACCESSORY_ATTACHED
UsbManager.ACTION_USB_ACCESSORY_DETACHED
UsbManager.ACTION_USB_DEVICE_ATTACHED
UsbManager.ACTION_USB_DEVICE_DETACHED
android.hardware.usb.action.USB_PORT_CHANGED
Intent.ACTION_BATTERY_CHANGED
SCREEN_ON / SCREEN_OFF / USER_PRESENT
```

Если конкретный broadcast Samsung/Android не доставляет обычному приложению — это допустимо; отсутствие события не должно ломать сессию.

## 6. Полный USB_STATE event log

Каждый полученный `USB_STATE` пишется в `usb_state_events.txt`, даже если состояние совпадает с предыдущим.

Обязательные поля:

```text
seq
wall
iso
elapsed
elapsed_nanos
delta_prev_ms
source
extras
```

`source` различает обычную доставку и initial sticky intent.

Минимально парсятся extras:

```text
connected
configured
accessory
mtp
ptp
adb
```

Дополнительно сохраняются все безопасно сериализуемые primitive/string extras, чтобы не потерять Samsung-specific сигналы.

Parcelable/opaque объекты не сериализуются целиком; пишется только имя класса.

## 7. Handshake checkpoints

После каждого перехода `configured=false → true` ставятся пассивные таймеры:

```text
2 s
5 s
10 s
30 s
```

Если на checkpoint всё ещё:

```text
connected=true
configured=true
accessory=false
```

запись идёт в `handshake_checkpoints.txt`.

На 10 s дополнительно фиксируется stuck handshake.

Таймеры не выполняют системных USB-запросов.

## 8. Re-enumeration metrics

Считать:

- `configured_rise_count`;
- `configured_drop_while_connected_count`;
- `reenumeration_count`;
- `stuck_handshake_count`;
- `initial_config_to_accessory_ms`;
- `last_config_to_accessory_ms`;
- `ptp_to_config_drop_ms`;
- `ptp_to_accessory_ms`;
- `config_drop_to_accessory_ms`.

## 9. Автоматическая классификация

Классификатор `HandshakeForensics` должен выставлять:

```text
PTP_REENUMERATION_RECOVERS_AOA
```

если в одной физической USB-сессии выполнено:

1. ранее был stuck `configured && !accessory`;
2. появился PTP;
3. после PTP был `configured` drop;
4. `accessory=true` появился не позднее 5 s после PTP.

Если был stuck, но recovery-паттерн не подтверждён:

```text
CONFIGURED_WITHOUT_ACCESSORY
```

Иначе:

```text
NO_STUCK_PATTERN
```

Результат пишется в `findings.txt`.

## 10. Clean / dirty marker

В `findings.txt` и `metrics.txt` писать:

```text
clean_base_case=true/false
```

`true` допускается только если:

- `wired_adb_seen=false`;
- verified enhanced Shizuku реально не использовался.

Если `adb=true` появился в проводном USB_STATE, кейс считается dirty независимо от дальнейшего результата Android Auto.

## 11. Accessory metadata

`UsbManager.getAccessoryList()` разрешено вызывать только после того, как `accessory=true` уже зафиксирован либо пришёл `ACTION_USB_ACCESSORY_ATTACHED`.

Сохранять в `accessory_info.txt`:

- manufacturer;
- model;
- description;
- version;
- URI после redaction.

Serial не сохранять.

## 12. Observer effect

В base mode запрещены:

- USB polling;
- `dumpsys usb`;
- Shizuku bind;
- logcat;
- Package usage/process probing;
- любые writes.

Локальная запись файлов и внутренние Handler timers допустимы.

## 13. Enhanced Shizuku — только opt-in

После явного пользовательского действия:

### До bind

```java
Shizuku.getUid() == 2000
```

При любом другом UID bind запрещён.

### После bind

```text
uid=2000
SELinux=u:r:shell:s0
```

Только после этого создаётся `VerifiedShell`.

При новом binder identity проверяется заново.

## 14. Shell API

AIDL:

```text
String exec(in String[] argv)
int uid()
String selinuxContext()
```

`sh -c` и строковый arbitrary shell запрещены.

`ShellUserService`:

1. проверяет UID + SELinux;
2. вызывает `ShellCommandPolicy.requireAllowed(argv)`;
3. запускает `new ProcessBuilder(argv)`;
4. не использует shell parser.

## 15. Exact argv allowlist enhanced mode

Разрешены только:

```text
/system/bin/getprop sys.usb.state
/system/bin/getprop sys.usb.config
/system/bin/getprop persist.sys.usb.config
/system/bin/dumpsys usb
/system/bin/ps -A
```

и фиксированный read-only logcat:

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

Запрещены любые другие executable/flags/arguments.

## 16. Android Auto

Основной процесс `com.google.android.projection.gearhead` сам по себе не считается доказательством проекции.

Только enhanced snapshot может искать:

```text
com.google.android.projection.gearhead:projection
```

Base mode не должен использовать process probing.

## 17. Privacy

До записи применяется redaction для:

- MAC/BSSID;
- quoted SSID;
- IPv4;
- email;
- serial-like values.

Приложение не объявляет `INTERNET`.

Экспорт только через SAF `ACTION_CREATE_DOCUMENT`.

В manifest:

```text
allowBackup=false
usesCleartextTraffic=false
dataExtractionRules=<exclude diagnostics>
```

## 18. Foreground Service

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

Partial wakelock только на время пользовательской сессии.

## 19. ZIP v0.2.1

Ожидаемые файлы:

```text
device.txt
timeline.txt
raw_usb.txt
usb_state_events.txt
usb_aux_events.txt
handshake_checkpoints.txt
accessory_info.txt
findings.txt
metrics.txt
snapshots.txt
log_repeat_stats.txt
usb_props.txt          (enhanced only)
logcat_filtered.txt    (enhanced only)
shell_identity.txt     (enhanced only)
```

## 20. Unit tests

Кроме security-тестов обязательны тесты `HandshakeForensics`:

- stuck + PTP + config drop + accessory <=5 s → `PTP_REENUMERATION_RECOVERS_AOA`;
- stuck + accessory без PTP → не классифицировать как PTP recovery;
- ADB или enhanced Shizuku → `clean_base_case=false`.

## 21. Build / CI

```text
JDK 17
Gradle Wrapper 8.13
Android Gradle Plugin 8.13.0
compileSdk 36
targetSdk 36
minSdk 29
versionCode 4
versionName 0.2.1
```

CI:

```bash
bash scripts/ci_safety_check.sh
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
aapt2 dump permissions <apk>
```

CI падает при появлении arbitrary shell, mutating shell literals, INTERNET/storage/install-packages/secure-settings/usage-stats permissions, accessibility/VPN components.

## 22. Acceptance Criteria

v0.2.1 допускается к полевому тесту, если:

1. обычный старт не bind-ит Shizuku;
2. base mode не выполняет shell/logcat/dumpsys;
3. каждый USB_STATE сохраняется отдельно;
4. primitive/string extras сохраняются, opaque parcelables не дампятся;
5. checkpoints 2/5/10/30 s не делают системных USB-запросов;
6. PTP re-enumeration classifier покрыт unit-тестами;
7. ADB автоматически делает кейс dirty;
8. accessory metadata читается только после появления accessory;
9. root/non-shell Shizuku блокируется до bind;
10. arbitrary shell отсутствует;
11. `INTERNET` и storage permissions отсутствуют в APK;
12. CI safety, unit tests, assembleDebug и permission check зелёные.

## 23. Полевой сценарий

Для чистого Porsche-теста:

```text
USB debugging OFF
Wireless debugging OFF
Shizuku не нужен
```

Порядок:

1. открыть PCM Link Doctor;
2. начать диагностику до подключения кабеля;
3. подключить Porsche PCM;
4. нажать `Подключил к авто`;
5. при провале — `AA не запустился`;
6. вручную выбрать PTP;
7. после запуска AA — `После PTP заработало`;
8. остановить;
9. экспортировать ZIP.
