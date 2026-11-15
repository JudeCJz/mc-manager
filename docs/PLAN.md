# MCManager — build plan and shared contract

Local, on-device Minecraft Java server manager for Android. Target phone: iQOO Neo 6
(I2202, Android 14 / SDK 34, Snapdragon 870 SM8250, arm64-v8a, 8 GB RAM, ~99 GB free).
Installed with `adb install`, not Play Store.

This file is the contract every coding agent follows. Do not change a signature in
`core/` without the planner (Claude) agreeing.

## Key technical decisions (do not revisit)

1. **Java runtime**: Android (bionic) builds from GitHub
   `AngelAuraMC/angelauramc-openjdk-build`, release tag `download`, assets
   `jre8-android-arm64.tar.xz`, `jre17-android-arm64.tar.xz`, `jre21-android-arm64.tar.xz`,
   `jre25-android-arm64.tar.xz` (≈27–38 MB each; tarball root is `./bin ./lib ./conf`).
   URL form: `https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download/jre21-android-arm64.tar.xz`.
   Desktop Temurin builds do NOT work (glibc). Downloaded on demand, extracted to
   `filesDir/runtimes/jre<major>/`, `chmod 755` on `bin/*` and `lib/**/*.so`
   (use `File.setExecutable(true,false)`), then `bin/java -version` is run to verify.
   **Verified on the phone 2026-11-07**: `bin/java -version` → "OpenJDK Android Runtime Environment 21.0.1".
   The launcher has no RUNPATH: every java exec MUST set env
   `LD_LIBRARY_PATH=<home>/lib:<home>/lib/server` and `JAVA_HOME=<home>`, otherwise
   "library libjli.so not found". The tarball contains symlinks; extract with commons-compress +
   xz (handle symlink entries with `Files.createSymbolicLink`, or copy the target), and guard
   against path traversal.
   **Pointer-tagging crash (verified)**: Android 11+ enables heap pointer tagging (TBI) for native
   executables and HotSpot aborts with "Pointer tag for 0x… was truncated". Fix (verified with a
   stress test on the phone): native shim `libmctagfix.so` (app/src/main/cpp/tagfix.c, built by
   CMake, NDK 27.3.13750724) whose constructor calls
   `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL /*-204*/, M_HEAP_TAGGING_LEVEL_NONE /*0*/)`.
   Every java exec MUST also set `LD_PRELOAD=<applicationInfo.nativeLibraryDir>/libmctagfix.so`
   (manifest: `android:extractNativeLibs="true"` so the .so exists on disk;
   `android:allowNativeHeapPointerTagging="false"` on <application> as well).
   Required Java per MC version comes from Mojang's per-version JSON `javaVersion.majorVersion`
   (fallback table: ≤1.16.5 → 8, 1.17.x → 17 (16 ok), 1.18–1.20.4 → 17, 1.20.5–1.21.x → 21, 26.x+ → 25).
2. **targetSdk = 28** (minSdk 26, compileSdk 36). Reason: since targetSdk 29 Android's SELinux
   forbids exec()/mmap-exec of files in the app's data dir, which a downloaded JRE needs. Termux
   uses the same approach. Consequences: no foregroundServiceType required, POST_NOTIFICATIONS is
   auto-prompted when the first channel is created, legacy storage rules. Still use SAF for
   import/export. Add `tools:ignore="ExpiredTargetSdkVersion"` / lint disable so the build passes.
3. **Server = child process** started with `ProcessBuilder` from `ServerService` (foreground
   service, `android:process` default — same process as UI). stdout+stderr merged
   (`redirectErrorStream(true)`) and read on `Dispatchers.IO`; stdin used for commands.
   Android 12+ "phantom process killer" can kill it — README + `tools/optimize_phone.ps1` cover the
   adb fix. Do not implement in-process JVM/JNI.
4. **Engines**: PAPER, PURPUR, FABRIC, VANILLA = full support. FORGE = experimental (download
   installer, run `java -jar installer.jar --installServer` with the bundled JRE, launch through
   `@libraries/net/minecraftforge/forge/<ver>/unix_args.txt`). SPIGOT = not buildable on-device
   (BuildTools needs git + lots of RAM): show it in the engine list as disabled with text
   "Use Paper — it runs Spigot plugins". Never pretend it works.
5. **APIs** (agents must check live with curl before coding, they may have moved):
   - Paper: `https://fill.papermc.io/v3/projects/paper` (v3 "Fill"; v2 `api.papermc.io/v2` is deprecated — use whichever answers, prefer v3)
   - Purpur: `https://api.purpurmc.org/v2/purpur`, `.../{ver}/latest/download`
   - Fabric: `https://meta.fabricmc.net/v2/versions/game`, `/loader`, `/installer`,
     server jar `https://meta.fabricmc.net/v2/versions/loader/{game}/{loader}/{installer}/server/jar`
   - Vanilla: `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` → version JSON → `downloads.server.url`
   - Forge: `https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json`,
     installer `https://maven.minecraftforge.net/net/minecraftforge/forge/{mc}-{forge}/forge-{mc}-{forge}-installer.jar`
   - Modrinth `https://api.modrinth.com/v2`, Hangar `https://hangar.papermc.io/api/v1`
   - User-Agent for all requests: `MCManager/<versionName> (android)` (no email).
6. **Defaults**: Xmx 3072 MB, Xms 1024 MB, port 25565, Aikar flags preset. RAM warning when the
   sum of Xmx of running+starting servers exceeds 50 % of `ActivityManager.MemoryInfo.totalMem`
   (≈3.9 GB on this phone) and a hard "are you sure" above 65 %.
7. **Storage**: default server root `filesDir/servers/<id>-<slug>/` (fast internal storage).
   Settings option "external app storage" = `getExternalFilesDir(null)/servers` (visible over USB,
   slower). JREs always in internal `filesDir/runtimes` (exec only works there).

## Stack and versions

Same toolchain as `../JNToolz` (known-good on this PC): Gradle 9.1.0 wrapper, AGP 9.0.1,
Kotlin 2.3.20 (AGP 9 built-in Kotlin — do not apply `org.jetbrains.kotlin.android`),
Compose BOM 2026.03.01, JDK 21 at `C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot`, toolchain 17.
Add: Hilt (+ hilt-navigation-compose, hilt-work), KSP, Room (+ktx), DataStore Preferences,
WorkManager, Navigation Compose, kotlinx-serialization-json, OkHttp, Apache commons-compress +
org.tukaani:xz (for .tar.xz), SnakeYAML (engine `org.yaml:snakeyaml`), AppCompat (per-app
language). Pick versions compatible with AGP 9 / Kotlin 2.3.20 (Hilt ≥ 2.59, KSP matching).

Package: `com.mcmanager`. applicationId `com.mcmanager`. versionName `0.1.0`.
APK output name `MCManager-v<versionName>-<buildType>.apk`.

## Package layout and file ownership

```
app/src/main/java/com/mcmanager/
  MCManagerApp.kt  MainActivity.kt                      [foundation]
  core/model/        Engine, ServerState, RuntimeState, ConsoleLine, ...   [foundation]
  core/data/db/      AppDatabase, ServerEntity, ServerDao, BackupEntity, BackupDao, Converters [foundation]
  core/data/settings/ SettingsRepository, AppSettings                     [foundation]
  core/data/ServerRepository.kt                                            [foundation]
  core/net/          NetModule (OkHttpClient, Json), Http.kt helpers (getJson, download with progress) [foundation]
  core/engine/       contracts: JavaRuntimeManager, VersionRepository, ServerInstaller, ServerController (interfaces only) [foundation]
  core/ui/theme/     Theme.kt (light/dark/AMOLED), Color, Type              [foundation]
  core/ui/nav/       Routes.kt, AppNavHost.kt                               [foundation]
  core/ui/components/ shared small composables (ConfirmDialog, LoadingBox, SectionHeader, EmptyState) [foundation]
  core/util/         FileUtils (dirSize, slug, zip helpers), RamUtils, NetUtils(lanIp) [foundation]
  di/                AppModule (db, datastore, dispatchers) + EngineBindings  [foundation, engine agent edits EngineBindings]

  engine/            JavaRuntimeManagerImpl, VersionRepositoryImpl, ServerInstallerImpl, providers/*, JvmFlags.kt [engine agent]
  service/           ServerService, ServerControllerImpl, ServerProcess, StatsSampler, LogParser, Notifications, BootstrapReceiver? [engine agent]

  feature/dashboard/ feature/create/ feature/console/ feature/logs/ feature/settings/ feature/server/ (server hub screen) [ui agent]
  feature/files/ feature/config/ feature/players/                          [files agent]
  feature/plugins/ feature/versions/                                       [plugins agent]
  feature/backups/ feature/network/ (+ work/BackupWorker)                  [ops agent]
app/src/main/res/raw/jvm_presets.json                                      [engine agent]
app/src/main/res/values/strings_<feature>.xml + values-hi/strings_<feature>.xml — one pair per agent
  (foundation: strings.xml; ui: strings_ui.xml; files: strings_files.xml; plugins: strings_plugins.xml; ops: strings_ops.xml; engine: strings_engine.xml)
```

Each feature folder owns its `XxxScreen.kt` + `XxxViewModel.kt` (`@HiltViewModel`) + any
repository it needs. Foundation creates a placeholder `XxxScreen` composable in every feature
folder so the nav graph compiles; the owning agent replaces the file contents but keeps the
composable name and parameters listed under "Routes".

## Core contract (foundation writes these exactly)

```kotlin
package com.mcmanager.core.model
enum class Engine(val label: String, val supported: Boolean, val experimental: Boolean, val isModLoader: Boolean) {
  PAPER("Paper", true, false, false), PURPUR("Purpur", true, false, false),
  FABRIC("Fabric", true, false, true), FORGE("Forge", true, true, true),
  VANILLA("Vanilla", true, false, false), SPIGOT("Spigot", false, false, false)
}
enum class ServerState { STOPPED, INSTALLING, STARTING, RUNNING, STOPPING, CRASHED }
data class RuntimeState(
  val state: ServerState = ServerState.STOPPED,
  val pid: Long? = null,
  val startedAt: Long? = null,          // epoch ms
  val cpuPercent: Float? = null,        // process CPU % of one core *  (0..cores*100)
  val rssMb: Int? = null,
  val tps: Double? = null,              // null if engine can't report
  val onlinePlayers: List<String> = emptyList(),
  val maxPlayers: Int? = null,
  val lastExitCode: Int? = null,
  val message: String? = null,          // e.g. install progress text or crash reason
  val progress: Float? = null,          // 0..1 during INSTALLING
)
data class ConsoleLine(val seq: Long, val time: Long, val text: String, val kind: Kind) {
  enum class Kind { OUT, ERR, CMD, SYSTEM } // CMD = echoed user command, SYSTEM = manager messages
}
data class InstallProgress(val step: String, val fraction: Float?)  // fraction null = indeterminate
data class JavaRuntime(val major: Int, val home: java.io.File, val javaBin: java.io.File, val versionString: String)
```

```kotlin
package com.mcmanager.core.data.db
@Entity(tableName = "servers")
data class ServerEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val engine: Engine,
  val mcVersion: String,
  val engineBuild: String? = null,      // paper/purpur build number, fabric loader ver, forge ver
  val dir: String,                      // absolute path of server directory
  val launchSpec: String? = null,       // e.g. "server.jar" or "@libraries/.../unix_args.txt"; null = not installed
  val port: Int = 25565,
  val xmxMb: Int = 3072,
  val xmsMb: Int = 1024,
  val jvmPreset: String = "aikar",      // id from res/raw/jvm_presets.json
  val gc: String = "G1",                // G1 | ZGC | Shenandoah | Parallel | Serial
  val extraJvmArgs: String = "",
  val javaMajor: Int = 21,
  val customJavaPath: String? = null,   // overrides managed runtime
  val eulaAccepted: Boolean = false,
  val autoRestart: Boolean = true,
  val backupIntervalHours: Int = 0,     // 0 = off
  val backupKeep: Int = 5,
  val seed: String = "",
  val gamemode: String = "survival",
  val createdAt: Long = System.currentTimeMillis(),
  val lastStartedAt: Long? = null,
)
@Entity(tableName = "backups")
data class BackupEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val serverId: Long, val file: String, val sizeBytes: Long, val createdAt: Long,
  val label: String, val auto: Boolean,
)
@Dao interface ServerDao {
  @Query("SELECT * FROM servers ORDER BY createdAt") fun observeAll(): Flow<List<ServerEntity>>
  @Query("SELECT * FROM servers WHERE id=:id") fun observe(id: Long): Flow<ServerEntity?>
  @Query("SELECT * FROM servers WHERE id=:id") suspend fun get(id: Long): ServerEntity?
  @Query("SELECT * FROM servers") suspend fun getAll(): List<ServerEntity>
  @Insert suspend fun insert(s: ServerEntity): Long
  @Update suspend fun update(s: ServerEntity)
  @Delete suspend fun delete(s: ServerEntity)
}
@Dao interface BackupDao {
  @Query("SELECT * FROM backups WHERE serverId=:sid ORDER BY createdAt DESC") fun observeFor(sid: Long): Flow<List<BackupEntity>>
  @Query("SELECT * FROM backups WHERE serverId=:sid ORDER BY createdAt DESC") suspend fun listFor(sid: Long): List<BackupEntity>
  @Insert suspend fun insert(b: BackupEntity): Long
  @Delete suspend fun delete(b: BackupEntity)
}
```
`ServerRepository` (@Singleton) wraps ServerDao: `observeAll()`, `observe(id)`, `get(id)`, `create(entity): Long`
(also creates the directory), `update(entity)`, `delete(id, deleteFiles: Boolean)`, `serverRoot(): File`.

```kotlin
package com.mcmanager.core.data.settings
data class AppSettings(
  val defaultXmxMb: Int = 3072, val defaultXmsMb: Int = 1024,
  val defaultJvmPreset: String = "aikar", val defaultGc: String = "G1",
  val globalJavaPath: String? = null,          // null = managed runtimes
  val autoRestartOnCrash: Boolean = true,
  val autoBackupHours: Int = 0,                // default for new servers
  val theme: ThemeMode = ThemeMode.SYSTEM,     // SYSTEM, LIGHT, DARK, AMOLED
  val language: String = "en",                // "en" | "hi"
  val storage: StorageLocation = StorageLocation.INTERNAL, // INTERNAL, EXTERNAL_APP
  val eulaConfirmedOnce: Boolean = false,
  val keepScreenAwakeOnConsole: Boolean = false,
  val consoleMaxLines: Int = 5000,
  val playitEnabled: Boolean = false, val ngrokEnabled: Boolean = false, val ngrokAuthToken: String = "",
)
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class StorageLocation { INTERNAL, EXTERNAL_APP }
// SettingsRepository: val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings)
```

```kotlin
package com.mcmanager.core.engine
interface JavaRuntimeManager {
  fun installed(): List<JavaRuntime>
  suspend fun ensure(major: Int, onProgress: (InstallProgress) -> Unit = {}): JavaRuntime  // downloads if missing
  suspend fun remove(major: Int)
  /** Java binary for a server: customJavaPath > settings.globalJavaPath > managed runtime (ensure). */
  suspend fun javaFor(server: ServerEntity, onProgress: (InstallProgress) -> Unit = {}): java.io.File
}
interface VersionRepository {
  suspend fun mcVersions(engine: Engine, includeSnapshots: Boolean = false): List<String> // newest first
  suspend fun latestBuild(engine: Engine, mcVersion: String): String?   // build id / loader version
  suspend fun requiredJava(mcVersion: String): Int
}
interface ServerInstaller {
  /** Downloads/installs jar for (engine, mcVersion, build). Writes into server.dir. Returns launchSpec. */
  suspend fun install(server: ServerEntity, build: String?, onProgress: (InstallProgress) -> Unit): String
  /** Checks whether a newer build/version exists. null = up to date / unknown. */
  suspend fun updateAvailable(server: ServerEntity): String?
}
interface ServerController {
  val runtimes: StateFlow<Map<Long, RuntimeState>>          // only servers that were touched; missing = STOPPED
  fun runtime(id: Long): StateFlow<RuntimeState>
  fun console(id: Long): StateFlow<List<ConsoleLine>>        // ring buffer, newest last
  suspend fun start(id: Long): Result<Unit>                  // ensures java, eula.txt, server.properties port, starts service
  suspend fun stop(id: Long)                                 // sends "stop", waits up to 30s, then kills
  suspend fun restart(id: Long)
  fun kill(id: Long)
  fun sendCommand(id: Long, command: String)                 // no leading slash needed; echoes as CMD line
  suspend fun stopAll()
}
```
`ServerController` impl is a `@Singleton` owned by the engine agent; foundation ships a stub impl
(`StubServerController`, `StubJavaRuntimeManager`, …) bound in `di/EngineBindings.kt` so the app
compiles; the engine agent replaces the bindings and deletes the stubs.

## Routes (foundation creates; owners fill)

`Routes.kt` defines string routes; `AppNavHost` wires them with a bottom bar
(Servers, Settings) and a server hub.

| Route | Composable (owner) |
|---|---|
| `dashboard` | `DashboardScreen(onOpenServer:(Long)->Unit, onCreate:()->Unit)` (ui) |
| `create` | `CreateServerScreen(onDone:(Long)->Unit, onBack:()->Unit)` (ui) |
| `server/{id}` | `ServerHubScreen(serverId, navigate:(String)->Unit, onBack)` (ui) — status card, start/stop/restart/kill, stats, LAN address, tiles linking to every sub-screen below |
| `server/{id}/console` | `ConsoleScreen(serverId, onBack)` (ui) |
| `server/{id}/logs` | `LogsScreen(serverId, onBack)` (ui) — browse `logs/*.log(.gz)`, search, export via SAF |
| `server/{id}/files?path={path}` | `FilesScreen(serverId, startPath, onBack, onEditFile:(String)->Unit)` (files) |
| `server/{id}/edit?path={path}` | `TextEditorScreen(serverId, path, onBack)` (files) |
| `server/{id}/properties` | `PropertiesScreen(serverId, onBack)` (files) |
| `server/{id}/yaml?file={file}` | `YamlConfigScreen(serverId, file, onBack)` (files) — bukkit.yml, spigot.yml, config/paper-global.yml, purpur.yml |
| `server/{id}/players` | `PlayersScreen(serverId, onBack)` (files) — tabs Online / Whitelist / Ops / Bans |
| `server/{id}/plugins` | `PluginsScreen(serverId, onBack)` (plugins) — installed + browse Modrinth/Hangar |
| `server/{id}/versions` | `VersionsScreen(serverId, onBack)` (plugins) |
| `server/{id}/backups` | `BackupsScreen(serverId, onBack)` (ops) |
| `server/{id}/network` | `NetworkScreen(serverId, onBack)` (ops) — LAN IP, port change, UPnP port forward, public IP / CGNAT check, IPv6, playit.gg, ngrok, latency tuning |
| `server/{id}/settings` | `ServerSettingsScreen(serverId, onBack)` (ui) — RAM, JVM preset, GC, extra args, java, auto-restart, EULA, delete server |
| `settings` | `SettingsScreen()` (ui) |

## Cross-agent hooks

- Players/kick/ban/op actions go through `ServerController.sendCommand`.
- Online players + TPS come from `RuntimeState` (engine agent parses log lines: `joined the game`,
  `left the game`, Paper `tps` output, `There are X of a max of Y players online`; it polls `list`
  once at start and `tps` every 30 s only for PAPER/PURPUR, without echoing those polls to console).
- Version switching (plugins agent) calls `ServerInstaller.install` and updates `ServerEntity`;
  it must require the server to be STOPPED and offer a backup first via `BackupManager.createBackup`
  (ops agent; contract: `@Singleton class BackupManager { suspend fun createBackup(serverId: Long, label: String, auto: Boolean = false): Result<BackupEntity>; suspend fun restore(backupId: Long): Result<Unit>; suspend fun delete(backupId: Long); suspend fun applyRetention(serverId: Long) }` in `feature/backups/BackupManager.kt`). Foundation creates this class with TODO bodies that return failure; ops agent fills it.
- Scheduled backups: ops agent's `BackupScheduler.schedule(serverId, hours)` (WorkManager periodic),
  called from ServerSettingsScreen (ui) — contract: `@Singleton class BackupScheduler { fun schedule(serverId: Long, hours: Int) ; fun cancel(serverId: Long) }` in `feature/backups/`. Foundation stubs it.
- Notification "Stop" action and service lifecycle: engine agent only.

## Rules for every agent

- Kotlin, coroutines, Flow; no blocking calls on Main. Material 3 Compose. `@HiltViewModel`.
- Only touch files you own. If you need a contract change, write it in your final report instead.
- Use `strings_<you>.xml` for UI text and give a Hindi translation in `values-hi/` with the same keys.
- No telemetry, no accounts. Never claim a feature works that you did not implement — list gaps in your report.
- Build with `.\gradlew.bat :app:compileDebugKotlin` (PowerShell, from `mcmanager/`). Other agents work
  in parallel, so errors in files you do not own are not yours: fix only your own.
- Never run anything on the phone (no adb). The planner does device testing.
- Final report: files written, what works, what is stubbed/untested, contract issues.
