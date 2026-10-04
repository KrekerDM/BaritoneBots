# Progress: stage plugin (:companion-plugin)

Package root: io.github.krekerdm.baritonebots.plugin

## State found when attempt 2 started
Present from attempt 1 (kept after review): build.gradle.kts, Tokens, AddressRules, PluginSettings, Messages,
BotRegistry, BotPerks, CompanionChannel, ForceLogin, journal/* (JournalCodec, JournalFiles, JournalIndex,
JournalRecord, JournalService, JournalStore, JournalSummary, TimeWindow), rollback/* (RollbackJob,
RollbackResult, RollbackRules). Empty resources/lang dir. Missing: main class BaritoneBotsPlugin, listeners,
handshake handler, rollback service, admin command, plugin.yml, config.yml, lang files, tests.

## Log
- stage plugin: attempt 2 started; reviewed existing code; next: verify Paper API via javap, write missing classes.
- done (2026-10-04): module re-included in settings.gradle.kts. Added BaritoneBotsPlugin (config + token on
  first start, journal store/service lifecycle, verify/unverify, perks, force login, reload, clean shutdown),
  CompanionProtocol (hello/welcome/reject, rollback/rollback_result, journal/journal_result, notice),
  BotListener (AsyncPlayerPreLoginEvent name protection, respawn/world-change perk upkeep, quit, block journal at
  MONITOR), AdminCommand (status/reload/rollback/journal/token + tab completion), rollback/RollbackService
  (journal batches or console command template), plugin.yml, config.yml (RU+EN comments), lang/en|ru.properties.
  JUnit: JournalTest (codec, time window, index, retention), AddressRulesTest, RollbackRulesTest (+ Tokens).
  Cross-module: BotStatus.dayTime (mod StatusReporter, manager ScheduleService/DayNight). Docs: docs/PLUGIN.md,
  README, SPEC §2.5/§5.7b, DECISIONS. Whole-project `gradlew build` green.
- next: live test on the Paper 26.2 test server with a real bot (handshake, distances, AuthMe force login,
  journal + rollback of a few hundred blocks, command mode with CoreProtect or Prism, name protection kick).
