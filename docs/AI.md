# Локальный ИИ: поле команд и диспетчер

[English below](#local-ai-command-box-and-dispatcher)

Два необязательных помощника поверх планировщика, оба выключены по умолчанию.
Поле команд превращает фразу вроде «развиться до железки» в план для ботов.
Диспетчер раз в 90 с читает сводку запущенного проекта и пишет, что происходит
и что стоит поменять. Модель работает в Ollama на этом же компьютере, адрес
по умолчанию `http://127.0.0.1:11434`. Менеджер не обращается к облачным
моделям и не хранит ключей.

Раздачу работы ботам ИИ не берёт на себя: каждые 5 с её по-прежнему делает
планировщик. Без Ollama панель и боты работают так же, как с выключенным ИИ.

## Установка

1. Установите Ollama: <https://ollama.com/download> (Windows, macOS, Linux).
2. Скачайте модель:
   ```
   ollama pull qwen2.5:7b-instruct
   ```
   На диске она занимает около 4,7 GB, в работе — около 5 GB оперативной или
   видеопамяти.
3. В панели: «Настройки» → «ИИ» → «Включить ИИ» → «Сохранить», затем
   «Проверить подключение». Должно быть «отвечает» и «установлена».

| Настройка | По умолчанию | Что делает |
|---|---|---|
| `ai.enabled` | выкл. | показывает поле команд и диспетчер |
| `ai.endpoint` | `http://127.0.0.1:11434` | адрес Ollama |
| `ai.model` | `qwen2.5:7b-instruct` | имя модели в Ollama |
| `ai.timeoutSec` | 30 с | сколько ждать ответа на один запрос |
| `ai.superviseSec` | 90 с | как часто диспетчер разбирает каждый запущенный проект |
| `ai.mode` | «Предлагать» | действия диспетчера ждут вашего решения или применяются сразу |

Первый запрос после запуска Ollama дольше обычного: модель загружается в память.
Если он не укладывается в 30 с, увеличьте «Ожидание ответа».

## Поле команд

Есть на экране «Боты» и на странице бота. Введите задачу и нажмите
«Разобрать». Откроется план: задачи по ботам, постоянные заказы, проект,
пояснение модели и отклонённые пункты с причиной. Запускается он только
кнопкой «Подтвердить».

| Фраза | План |
|---|---|
| «развиться до железки» | `progress iron` свободному боту |
| «собери 64 железа и сложи в склад» | `obtain minecraft:iron_ingot 64`, затем `deposit_storage`, на одном боте |
| «всегда держи 128 факелов на складе» | постоянный заказ: факелы, минимум 128, склад |
| «построй замок из castle.schem у дома всеми ботами» | проект строительства, место у дома ищется автоматически, все боты |

Если в таблице отмечены боты, план только для них. На странице бота — только
для этого бота. Задачи с «любым ботом» получает один свободный бот в сети,
по порядку.

## Диспетчер

Раздел «Диспетчер» на странице проекта. Разбор идёт каждые `ai.superviseSec`
секунд и раньше — после блокировки проекта, второго застревания бота за
10 минут или гибели бота, но не чаще раза в 30 с. Кнопка «Разобрать сейчас»
запускает разбор вручную.

Разбор — это сводка простыми словами («43 %, Bot2 ждёт стекло, песка нет»)
и до пяти действий. В режиме «Предлагать» действия ждут кнопок «Применить» /
«Отклонить»; следующий разбор помечает нерешённые как «устарело». В режиме
«Применять сразу» они выполняются сразу. Каждое применённое действие попадает
в журнал проекта и в события вместе с причиной.

## Что ИИ может

- поставить задачи и шаги из каталога ботам, которых вы выбрали;
- добавить постоянный заказ или поднять уже существующий;
- создать и запустить проект после вашего подтверждения;
- в диспетчере: сменить приоритет проекта, перевести бота на роль, добавить
  заказ, приостановить или продолжить этот проект, дать боту одну задачу,
  написать вам сообщение.

## Чего ИИ не может

- запустить что-либо без «Подтвердить» (поле команд) или вне списка выше
  (диспетчер);
- отдавать сырые команды Baritone, сетки крафта и координаты, которых нет
  в вашем тексте;
- ссылаться на ботов, точки, области, наборы, профили и схемы, которых нет;
- менять другие настройки, удалять что-либо, трогать другие проекты.

Каждый ответ модели проверяется по живому каталогу до показа и ещё раз перед
запуском. Пункт, не прошедший проверку, показывается с причиной и не
выполняется. Если Ollama не отвечает, поле команд пишет об этом, а в ленте
диспетчера появляется запись «Ollama не отвечает»; планировщик работает дальше.

---

# Local AI: command box and dispatcher

Two optional helpers on top of the planner, both off by default. The command
box turns a phrase like "progress to iron tools" into a plan for the bots. The
dispatcher reads a summary of each running project every 90 s and says what is
going on and what to change. The model runs in Ollama on the same computer,
default address `http://127.0.0.1:11434`. The manager calls no cloud model and
stores no keys.

The AI does not hand out work: the planner still does that every 5 s. Without
Ollama the panel and the bots behave as with the AI switched off.

## Setup

1. Install Ollama: <https://ollama.com/download> (Windows, macOS, Linux).
2. Pull the model:
   ```
   ollama pull qwen2.5:7b-instruct
   ```
   It takes about 4.7 GB on disk and about 5 GB of RAM or VRAM while running.
3. In the panel: Settings → AI → Enable AI → Save, then Check connection. It
   should say "answers" and "installed".

| Setting | Default | What it does |
|---|---|---|
| `ai.enabled` | off | shows the command box and the dispatcher |
| `ai.endpoint` | `http://127.0.0.1:11434` | Ollama address |
| `ai.model` | `qwen2.5:7b-instruct` | model name in Ollama |
| `ai.timeoutSec` | 30 s | how long to wait for one answer |
| `ai.superviseSec` | 90 s | how often the dispatcher reviews each running project |
| `ai.mode` | Suggest | dispatcher actions wait for your decision, or apply at once |

The first request after Ollama starts takes longer: the model is loaded into
memory. If it does not fit into 30 s, raise "Answer timeout".

## Command box

On the Bots screen and on a bot's page. Type the task and press Parse. The
plan opens: tasks per bot, standing orders, a project, the model's note and
the rejected items with their reason. Only Confirm runs it.

| Phrase | Plan |
|---|---|
| "progress to iron tools" | `progress iron` for a free bot |
| "get 64 iron and put it in storage" | `obtain minecraft:iron_ingot 64`, then `deposit_storage`, on one bot |
| "always keep 128 torches in storage" | standing order: torch, at least 128, storage |
| "build castle.schem near home with all bots" | build project, site near home found automatically, all bots |

When bots are ticked in the table, the plan uses only them; on a bot's page
only that bot. Tasks for "any bot" go to one free online bot, in order.

## Dispatcher

The Dispatcher section on a project's page. A review runs every
`ai.superviseSec` seconds and earlier after the project is blocked, a bot gets
stuck twice within 10 minutes or a bot dies, at most once per 30 s. Review now
starts one by hand.

A review is a plain summary ("43 %, Bot2 waits for glass, no sand") and up to
five actions. In Suggest mode they wait for Apply / Dismiss; the next review
marks undecided ones as superseded. In Apply at once mode they run immediately.
Every applied action goes into the project's log and the events with its reason.

## What the AI can do

- queue catalog tasks and steps on the bots you chose;
- add a standing order or raise an existing one;
- create and start a project after you confirm;
- in the dispatcher: change the project's priority, move a bot to a role, add
  a standing order, pause or resume this project, give a bot one task, send you
  a message.

## What the AI cannot do

- run anything without Confirm (command box) or outside the list above
  (dispatcher);
- send raw Baritone commands, crafting grids or coordinates that are not in
  your text;
- name bots, waypoints, areas, kits, profiles or schematics that do not exist;
- change other settings, delete anything, touch other projects.

Every model answer is checked against the live catalog before it is shown and
again before it runs. An item that fails the check is shown with its reason and
does not run. When Ollama does not answer, the command box says so and the
dispatcher feed gets an "Ollama does not answer" entry; the planner keeps going.
