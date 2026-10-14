# PCM Link Doctor

Диагностическое Android-приложение для кейса **Samsung Galaxy ↔ проводной Android Auto ↔ Porsche PCM**.

## Что делает v0.1

- ничего не меняет в USB/Android Auto/PCM;
- работает без root через **Shizuku** (ADB shell privileges);
- фиксирует USB power, `sys.usb.state`, `sys.usb.config`, релевантный `dumpsys usb`;
- отслеживает `accessory` / AOA-переход и процесс Android Auto (`com.google.android.projection.gearhead`);
- собирает отфильтрованный `logcat` по USB / accessory / projection / Android Auto / MTP / PTP;
- автоматически отмечает подозрение на зависший handshake: USB есть >10 сек, accessory не появился;
- автоматически отмечает цепочку **PTP → accessory**, если accessory появляется в течение 15 секунд после PTP;
- две ручные метки: `AA не запустился` и `После PTP заработал`;
- экспортирует диагностическую сессию в ZIP.

## Как пользоваться на телефоне

1. Установить Shizuku.
2. Включить `Параметры разработчика → Беспроводная отладка`.
3. Запустить Shizuku через Wireless debugging.
4. Открыть PCM Link Doctor и выдать ему разрешение Shizuku.
5. **До подключения кабеля** нажать `Начать диагностику`.
6. Подключить телефон к Porsche как обычно.
7. Если Android Auto не стартовал — нажать `AA не запустился` **до** ручной смены USB-режима.
8. Переключить USB на `Передача изображений (PTP)` как обычно.
9. Когда Android Auto оживёт — нажать `После PTP заработал`.
10. Остановить диагностику → кнопка экспорта → отправить ZIP для анализа.

## Безопасность

`ShellUserService` выполняет только диагностические команды чтения. В v0.1 нет команд `setprop`, `svc usb`, `cmd usb set-functions`, изменения настроек Android Auto или PCM.
