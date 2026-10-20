package numunun.ouroboros.game

import numunun.ouroboros.config.Keys
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.BorderSnapshot
import numunun.ouroboros.core.ChainColor
import numunun.ouroboros.core.GameSession
import numunun.ouroboros.core.SessionSnapshot
import numunun.ouroboros.core.ShrinkSchedule
import numunun.ouroboros.core.ShrinkSnapshot
import numunun.ouroboros.core.TeamSnapshot
import numunun.ouroboros.core.teamSnapshots
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * 진행 상황 저장과 이어하기. plugins/Ouroboros/session.yml
 *
 * - 자동 저장: 설정한 주기마다
 * - 서버 종료: 저장만 하고 끝낸다 (파일 유지)
 * - 정상 종료(승리, /ouro stop): 파일 삭제
 *
 * 임시 파일에 쓰고 한 번에 바꿔치기하므로, 저장 중에 서버가 죽어도 기존 파일이 깨지지 않는다.
 */
class SaveService(
    private val plugin: Plugin,
    private val game: GameService,
    private val border: BorderSetup,
    private val shrink: BorderShrinkService,
    private val settings: SettingValues,
) : GameLifecycleListener {

    private val file = File(plugin.dataFolder, "session.yml")
    private var ticker: BukkitTask? = null

    val hasSave: Boolean get() = file.exists()

    /** 이어하기 중에는 시작 훅의 즉시 저장을 건너뛴다. 보더와 축소를 되살린 뒤에 저장한다. */
    private var resuming = false

    /** 새 게임이 덮어쓰기 전에 지난 판 파일을 한 부 남겨 둔다. */
    private val previousFile = File(plugin.dataFolder, "session.previous.yml")

    override fun onGameStart(session: GameSession) {
        if (!resuming) {
            backupPrevious()
            save()
        }
        val minutes = settings[Keys.AUTO_SAVE_MINUTES]
        if (minutes <= 0) return
        val period = minutes * 60L * 20L
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { save() }, period, period)
    }

    /** 못 읽은 파일이나 이어 가지 않은 지난 판이 새 게임에 덮이지 않게 */
    private fun backupPrevious() {
        if (!file.exists()) return
        runCatching { Files.copy(file.toPath(), previousFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { plugin.logger.warning("지난 저장 파일을 백업하지 못했습니다: ${it.message}") }
    }

    /** 승부가 났을 때만 파일을 지운다. 중단이나 서버 종료는 이어 갈 수 있게 남긴다. */
    override fun onGameEnd() {
        ticker?.cancel()
        ticker = null
        if (game.lastEndReason == EndReason.VICTORY) file.delete()
    }
    fun save(): Boolean {
        val session = game.session ?: return false
        val overworld = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NORMAL }
        val snapshot = SessionSnapshot(
            participants = session.participants,
            spectators = session.spectators,
            teams = session.teamSnapshots(),
            dormantMillis = game.forfeitSnapshot(),
            shrink = shrink.snapshot(),
            borderSize = if (settings[Keys.BORDER_ENABLED]) overworld?.worldBorder?.size else null,
            borderOriginals = border.originals(),
        )
        return runCatching { write(snapshot) }
            .onFailure { plugin.logger.severe("게임 저장 실패: ${it.message}") }
            .isSuccess
    }

    fun suspend() {
        if (!game.isRunning) return
        save()
        game.stop(announce = false, reason = EndReason.SHUTDOWN)
        plugin.logger.info("진행 중인 게임을 저장했습니다. 다음에 /ouro resume 으로 이어 갈 수 있습니다.")
    }

    /** @return 실패 이유. 성공하면 null */
    fun resume(): String? {
        if (!file.exists()) return "저장된 게임이 없습니다. (${file.path})"
        val snapshot = read().getOrElse { return "저장 파일을 읽지 못했습니다: ${it.message}" }

        resuming = true
        try {
            when (val result = game.resume(snapshot)) {
                is StartResult.Rejected -> return result.reason
                StartResult.Started -> Unit
            }
            border.resume(snapshot.borderOriginals, snapshot.borderSize)
            shrink.resume(snapshot.shrink)
        } finally {
            resuming = false
        }

        // 다 되살린 뒤의 상태가 곧 저장 파일과 같아야 한다.
        save()
        return null
    }

    // ── 파일 ──────────────────────────────────────────────

    private fun write(snapshot: SessionSnapshot) {
        val yaml = YamlConfiguration()
        yaml.set("version", FORMAT_VERSION)
        yaml.set("saved-at", Instant.now().toString())
        yaml.set("participants", snapshot.participants.map { it.toString() })
        yaml.set("spectators", snapshot.spectators.map { it.toString() })

        snapshot.teams.forEach { team ->
            yaml.set("teams.${team.color.name}.owner", team.owner.toString())
            yaml.set("teams.${team.color.name}.slaves", team.slaves.map { it.toString() })
        }
        snapshot.dormantMillis.forEach { (color, millis) -> yaml.set("forfeit.${color.name}", millis) }

        snapshot.shrink?.let {
            yaml.set("shrink.phase", it.phase)
            yaml.set("shrink.stage", it.stage.name)
            yaml.set("shrink.elapsed", it.elapsed)
        }
        snapshot.borderSize?.let { yaml.set("border.size", it) }
        snapshot.borderOriginals.forEach { (worldId, it) ->
            val base = "border.originals.$worldId"
            yaml.set("$base.center-x", it.centerX)
            yaml.set("$base.center-z", it.centerZ)
            yaml.set("$base.size", it.size)
        }

        plugin.dataFolder.mkdirs()
        val temp = File(plugin.dataFolder, "session.yml.tmp")
        yaml.save(temp)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun read(): Result<SessionSnapshot> = runCatching {
        // loadConfiguration 은 깨진 파일을 빈 설정으로 돌려주므로, 직접 load 해서 문법 오류를 드러낸다.
        val yaml = YamlConfiguration()
        yaml.load(file)

        val version = yaml.getInt("version", -1)
        require(version == FORMAT_VERSION) {
            "지원하지 않는 저장 형식입니다 (파일 v$version, 지원 v$FORMAT_VERSION). 파일은 그대로 두었습니다."
        }

        val teamsSection = yaml.getConfigurationSection("teams") ?: error("팀 정보가 없습니다")
        val teams = teamsSection.getKeys(false).map { key ->
            val owner = teamsSection.getString("$key.owner") ?: error("$key 팀의 주인이 없습니다")
            TeamSnapshot(ChainColor.valueOf(key), UUID.fromString(owner), uuids(yaml, "teams.$key.slaves"))
        }

        val forfeit = yaml.getConfigurationSection("forfeit")?.let { section ->
            section.getKeys(false).associate { ChainColor.valueOf(it) to section.getLong(it) }
        }.orEmpty()

        val shrinkState = if (yaml.contains("shrink.phase")) {
            ShrinkSnapshot(
                yaml.getInt("shrink.phase"),
                ShrinkSchedule.Stage.valueOf(yaml.getString("shrink.stage") ?: error("축소 단계가 없습니다")),
                yaml.getInt("shrink.elapsed"),
            )
        } else {
            null
        }

        val originals = yaml.getConfigurationSection("border.originals")?.let { section ->
            section.getKeys(false).associate { id ->
                UUID.fromString(id) to BorderSnapshot(
                    section.getDouble("$id.center-x"),
                    section.getDouble("$id.center-z"),
                    section.getDouble("$id.size"),
                )
            }
        }.orEmpty()

        SessionSnapshot(
            participants = uuids(yaml, "participants").toSet(),
            spectators = uuids(yaml, "spectators").toSet(),
            teams = teams,
            dormantMillis = forfeit,
            shrink = shrinkState,
            borderSize = if (yaml.contains("border.size")) yaml.getDouble("border.size") else null,
            borderOriginals = originals,
        )
    }.onFailure {
        plugin.logger.severe("저장된 게임을 읽지 못했습니다: ${it.message}")
    }

    private fun uuids(yaml: YamlConfiguration, path: String): List<UUID> =
        yaml.getStringList(path).map { UUID.fromString(it) }

    companion object {
        private const val FORMAT_VERSION = 1
    }
}