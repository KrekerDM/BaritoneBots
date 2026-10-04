# Companion plugin / Серверный плагин

`baritonebots-companion-<version>.jar` — an optional plugin for Paper / Purpur 26.2. Bots work without it. It only helps on a server you run yourself.

`baritonebots-companion-<version>.jar` — необязательный плагин для Paper / Purpur 26.2. Боты работают и без него. Он нужен только на вашем собственном сервере.

## What it adds / Что он даёт

Everything applies only to bots that passed the token check. A bot sends `hello` with the token after it joins, and the plugin answers `welcome`. Someone who joins with a bot's name but has no token gets nothing.

Всё действует только на ботов, прошедших проверку токеном. После входа бот шлёт `hello` с токеном, плагин отвечает `welcome`. Тот, кто зашёл с ником бота без токена, ничего не получает.

| Feature | EN | RU |
|---|---|---|
| distances | Smaller view, simulation and send distance per bot, no mob spawning around bots. Re-applied after respawn and world change. | Меньшие дальности прорисовки, симуляции и отправки чанков для каждого бота, без спавна мобов вокруг. Повторно применяются после возрождения и смены мира. |
| permissions | Permission nodes for the session, e.g. `grim.exempt`. Unlike a LuckPerms group, they follow the token, not the name. | Права на время сессии, например `grim.exempt`. В отличие от группы LuckPerms, они привязаны к токену, а не к нику. |
| force login | Logs the bot in through AuthMe or nLogin. The account must be registered once. | Входит за бота через AuthMe или nLogin. Аккаунт нужно один раз зарегистрировать. |
| name protection | A bot's name may join only from listed IPs, CIDR ranges or a DDNS host name. | С ником бота можно зайти только с указанных IP, сетей CIDR или DDNS-имени. |
| journal | Every block a verified bot breaks or places, in `plugins/BaritoneBots/journal` (gzip files per day, kept `retention-days` days). The last 24 h are also kept in memory. | Каждый блок, который проверенный бот сломал или поставил, в `plugins/BaritoneBots/journal` (gzip-файлы по дням, хранятся `retention-days` дней). Последние 24 ч ещё и в памяти. |
| rollback | Undo a bot's changes for the last N minutes: from the journal, in batches per tick, skipping blocks someone else changed since; or by running your logging plugin's command (Prism, CoreProtect). | Откат изменений бота за последние N минут: по журналу, порциями за тик, не трогая блоки, которые с тех пор изменил кто-то другой; или командой вашего плагина логов (Prism, CoreProtect). |

## Install / Установка

1. Put the jar into `plugins/` and start the server. The plugin writes `plugins/BaritoneBots/config.yml` with a new random token.
2. Add the bot names to `bots:` and run `/bb reload`.
3. Run `/bb token` and click the token to copy it. In the manager, open the server profile, enable `companion` and paste the token.
4. The bot reconnects. `/bb status` should show it as verified, and the manager logs a `companion verified` event.

1. Положите jar в `plugins/` и запустите сервер. Плагин создаст `plugins/BaritoneBots/config.yml` с новым случайным токеном.
2. Впишите ники ботов в `bots:` и выполните `/bb reload`.
3. Выполните `/bb token` и нажмите на токен, чтобы скопировать. В менеджере откройте профиль сервера, включите `companion` и вставьте токен.
4. Бот переподключается. `/bb status` покажет его как проверенного, а менеджер запишет событие `companion verified`.

A bot that was online before the plugin loaded is verified on its next join. After a token change every bot has to reconnect.

Бот, который был в сети до загрузки плагина, пройдёт проверку при следующем входе. После смены токена все боты должны переподключиться.

## Config / Настройки

Every option has a short RU + EN comment in `config.yml`. Apply changes with `/bb reload`.

У каждой настройки в `config.yml` есть короткий комментарий на русском и английском. Применить изменения: `/bb reload`.

| Key | Default | EN | RU |
|---|---|---|---|
| `language` | `en` | `ru` or `en` | `ru` или `en` |
| `token` | generated | shared with the manager; empty = generate a new one | общий с менеджером; пусто = создать новый |
| `server-name` | `""` | name reported to bots | имя сервера для ботов |
| `bots` | `[]` | bot player names | ники ботов |
| `protect-names.enabled`, `allowed-ips` | off, `[]` | IP, CIDR or host name | IP, CIDR или имя хоста |
| `distances.view / simulation / send` | 4 / 3 / 4 | 2..32, -1 = server value | 2..32, -1 = как у сервера |
| `distances.affects-spawning` | `false` | mobs spawn around bots | спавн мобов вокруг ботов |
| `permissions` | `[]` | nodes for verified bots, `-node` denies | права для проверенных ботов, `-право` запрещает |
| `force-login.enabled`, `delay-ticks` | on, 10 | AuthMe / nLogin | AuthMe / nLogin |
| `journal.enabled`, `retention-days`, `memory-limit` | on, 14, 100000 | block journal | журнал блоков |
| `rollback.mode` | `journal` | `journal` or `command` | `journal` или `command` |
| `rollback.command` | `""` | console command with `{player}` and `{minutes}` | консольная команда с `{player}` и `{minutes}` |
| `rollback.blocks-per-tick`, `max-blocks` | 500, 200000 | journal rollback speed and size limit | скорость и предел отката по журналу |
| `debug` | `false` | verbose console log | подробный лог |

Rollback command examples (check the syntax in your plugin's docs):

Примеры команды отката (синтаксис сверьте с документацией своего плагина):

```yaml
rollback:
  mode: command
  command: "co rollback u:{player} t:{minutes}m r:#global"   # CoreProtect
  # command: "prism rollback p:{player} t:{minutes}m"        # Prism
```

## Commands / Команды

Permission `baritonebots.admin` (default: op). Alias `/bb`.

Право `baritonebots.admin` (по умолчанию у операторов). Сокращение `/bb`.

| Command | EN | RU |
|---|---|---|
| `/bb status` | bots and their state, every feature's state, journal counters, running rollback | боты и их состояние, состояние каждой функции, счётчики журнала, текущий откат |
| `/bb reload` | re-read `config.yml`; bots removed from the list lose their perks | перечитать `config.yml`; боты, убранные из списка, теряют привилегии |
| `/bb rollback <bot> <minutes>` | undo the bot's changes for the last N minutes | откатить изменения бота за последние N минут |
| `/bb journal <bot> <minutes>` | how many blocks the bot broke and placed, and the last few | сколько блоков бот сломал и поставил, и последние из них |
| `/bb token` | show the token (click to copy) | показать токен (нажмите, чтобы скопировать) |

## From the manager / Из менеджера

The manager can send `rollback` and `journal` requests through a bot (`POST /api/bots/{id}/plugin`, payload `{"t":"rollback","d":{"target":"Bot1","minutes":30}}`). Answers come back as `plugin_rollback`, `plugin_journal` and `plugin_notice` events. The protocol is in [SPEC.md §7](SPEC.md).

Менеджер может отправить запросы `rollback` и `journal` через бота (`POST /api/bots/{id}/plugin`, payload `{"t":"rollback","d":{"target":"Bot1","minutes":30}}`). Ответы приходят событиями `plugin_rollback`, `plugin_journal` и `plugin_notice`. Протокол описан в [SPEC.md §7](SPEC.md).

## Limits / Ограничения

* The journal records only what a bot does with its own hands (break and place events). Blocks that fall, burn or drop off because of that change are not recorded.
* A journal rollback never overwrites a block that someone else changed after the bot. Such positions are reported as skipped.
* One journal rollback at a time.
* Journal rollback restores blocks, not chest contents or entities. For that, use `mode: command` with Prism or CoreProtect.

* Журнал записывает только то, что бот сделал сам (события ломания и установки). Блоки, которые из-за этого упали, сгорели или отвалились, не записываются.
* Откат по журналу не перезаписывает блок, который после бота изменил кто-то другой. Такие места считаются пропущенными.
* Одновременно идёт только один откат по журналу.
* Откат по журналу восстанавливает блоки, но не содержимое сундуков и не сущности. Для этого используйте `mode: command` с Prism или CoreProtect.
