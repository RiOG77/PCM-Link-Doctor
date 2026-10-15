# PCM Link Doctor

Диагностическое Android-приложение для кейса **Samsung Galaxy ↔ проводной Android Auto ↔ Porsche PCM**.

Текущая версия: **0.2.1 Knox-safe**.

## Что мы уже подтвердили

В двух чистых полевых тестах без Shizuku и без `adb` воспроизведён один и тот же сценарий:

1. USB подключается;
2. телефон достигает `connected=true, configured=true`;
3. `accessory=false` остаётся десятки секунд;
4. пользователь вручную включает PTP;
5. начинается USB re-enumeration (`configured true → false → true/false`);
6. примерно через 2.1–2.2 с появляется `accessory=true`;
7. Android Auto запускается.

Это означает, что PTP нужен не как рабочий режим Android Auto, а как триггер повторной USB-инициализации, после которой AOA/accessory handshake проходит.

Главный принцип проекта: **observe, do not modify**.

## Что нового в v0.2.1

### Clean base mode по умолчанию

Запуск диагностики больше **не включает Shizuku автоматически и не запрашивает его permission**.

Base mode рассчитан именно на чистый кейс, где USB debugging выключен. Это критично для текущего Samsung: при включённой USB debugging Android Auto запускается стабильно, поэтому такой тест считается грязным.

Enhanced/Shizuku теперь включается только вручную отдельным действием и используется только для A/B сравнения.

### Полный поток USB_STATE

Теперь сохраняется **каждый** `android.hardware.usb.action.USB_STATE`, даже если fingerprint состояния не изменился.

Файл:

```text
usb_state_events.txt
```

Для каждого broadcast пишутся:

- последовательный номер;
- wall clock;
- `elapsedRealtime`;
- `elapsedRealtimeNanos`;
- интервал от предыдущего broadcast;
- источник (`broadcast` / `register_sticky`);
- все безопасно сериализуемые extras.

Это позволяет увидеть повторные USB_STATE, скрытые дополнительные Samsung extras и точную последовательность re-enumeration.

### Handshake checkpoints

После каждого перехода в `configured=true` ставятся пассивные контрольные точки на 2, 5, 10 и 30 сек.

Файл:

```text
handshake_checkpoints.txt
```

На 10-й секунде без `accessory` фиксируется stuck handshake.

### Автоматическая классификация

Новый `HandshakeForensics` считает:

- число `configured` rise/drop;
- re-enumeration count;
- stuck handshake count;
- initial configured → accessory;
- last configured → accessory;
- PTP → config drop;
- PTP → accessory;
- config drop → accessory.

Если подтверждён сценарий:

```text
configured без accessory
→ PTP
→ config drop/re-enumeration
→ accessory <= 5 s
```

в `findings.txt` появляется:

```text
pattern=PTP_REENUMERATION_RECOVERS_AOA
```

Также приложение явно пишет:

```text
clean_base_case=true/false
```

Кейс считается clean, только если не замечен проводной `adb` и enhanced Shizuku реально не использовался.

### Accessory metadata

Только **после того, как accessory уже появился**, приложение read-only читает `UsbManager.getAccessoryList()` и сохраняет безопасные поля в:

```text
accessory_info.txt
```

Serial намеренно не сохраняется.

### Дополнительные USB broadcasts

Read-only listener также пишет, если Android реально доставляет приложению:

- `USB_PORT_CHANGED`;
- `ACTION_USB_DEVICE_ATTACHED/DETACHED`;
- `ACTION_USB_ACCESSORY_ATTACHED/DETACHED`.

Файл:

```text
usb_aux_events.txt
```

## Knox-safe / Shizuku policy

Проект не использует:

- root / `su`;
- Magisk / Sui;
- OEM Unlock;
- bootloader unlock;
- custom firmware/kernel/recovery;
- системные writes;
- изменение USB mode;
- изменение Android Auto/PCM.

Если пользователь вручную включает enhanced mode, до `bindUserService` обязательно проверяется:

```text
Shizuku.getUid() == 2000
```

После bind дополнительно:

```text
uid=2000
SELinux=u:r:shell:s0
```

Arbitrary shell отсутствует. `ShellUserService` принимает только argv и запускает `ProcessBuilder(argv)` без `sh -c`.

## Разрешённые shell-команды enhanced mode

Только точные read-only формы:

```text
/system/bin/getprop sys.usb.state
/system/bin/getprop sys.usb.config
/system/bin/getprop persist.sys.usb.config
/system/bin/dumpsys usb
/system/bin/ps -A
/system/bin/logcat ... -T <timestamp> -d
```

Base mode эти команды вообще не использует.

## Privacy

- `INTERNET` permission отсутствует;
- storage permissions отсутствуют;
- экспорт через SAF `ACTION_CREATE_DOCUMENT`;
- `allowBackup=false`;
- диагностические данные исключены из backup/device transfer;
- перед записью применяется redaction MAC/BSSID/SSID/IP/email/serial-like данных;
- полный `getprop` не используется.

## Основные файлы ZIP v0.2.1

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
usb_props.txt                 (только enhanced)
logcat_filtered.txt           (только enhanced)
log_repeat_stats.txt
shell_identity.txt            (только enhanced)
```

## Чистый тест в Porsche

1. USB debugging — OFF.
2. Wireless debugging — OFF.
3. Shizuku не нужен.
4. Запустить PCM Link Doctor **до подключения кабеля**.
5. Нажать `Начать диагностику`.
6. Подключить телефон к Porsche PCM.
7. Нажать `Подключил к авто`.
8. Если AA не стартовал — `AA не запустился`.
9. В Samsung вручную выбрать `Передача изображений / PTP`.
10. Когда AA запустился — `После PTP заработало`.
11. Остановить диагностику.
12. Экспортировать ZIP.

## Build

- JDK 17
- Gradle Wrapper 8.13
- Android Gradle Plugin 8.13.0
- compileSdk 36
- targetSdk 36
- minSdk 29

CI выполняет:

```bash
bash scripts/ci_safety_check.sh
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

и проверяет итоговый APK через `aapt2 dump permissions`.

## Ограничение

Base mode специально не пытается читать закрытые USB HAL/Type-C/kernel детали через ADB: это сделало бы текущий кейс нечистым. v0.2.1 усиливает именно непривилегированное наблюдение за реальной последовательностью Android USB events.
