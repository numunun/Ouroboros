package numunun.ouroboros

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import numunun.ouroboros.command.OuroCommand
import numunun.ouroboros.config.BukkitConfigSource
import numunun.ouroboros.config.SettingValues
import numunun.ouroboros.core.CombatLog
import numunun.ouroboros.feature.HudService
import numunun.ouroboros.feature.NameTagHider
import numunun.ouroboros.feature.PacketGlow
import numunun.ouroboros.feature.ProximityAlarm
import numunun.ouroboros.feature.TabHider
import numunun.ouroboros.feature.TrackerService
import numunun.ouroboros.game.BorderRescueService
import numunun.ouroboros.game.BorderSetup
import numunun.ouroboros.game.BorderShrinkService
import numunun.ouroboros.game.GameLifecycleListener
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.RespawnHoldService
import numunun.ouroboros.game.RoleService
import numunun.ouroboros.game.SaveService
import numunun.ouroboros.game.Scatter
import numunun.ouroboros.game.TeamTeleportService
import numunun.ouroboros.listener.CombatListener
import numunun.ouroboros.listener.DeathListener
import numunun.ouroboros.listener.PresenceListener
import numunun.ouroboros.listener.RulesListener
import numunun.ouroboros.world.SafeGround
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

/** 의존성 조립만 담당한다. 로직은 각 서비스가 가진다. */
class Ouroboros : JavaPlugin() {

    lateinit var settings: SettingValues
        private set

    lateinit var configSource: BukkitConfigSource
        private set

    private lateinit var game: GameService
    private lateinit var saves: SaveService

    /** ProtocolLib 쪽 기능들. 클래스를 직접 참조하지 않도록 인터페이스 타입으로 둔다. */
    private val closeables = ArrayList<AutoCloseable>()

    override fun onEnable() {
        configSource = BukkitConfigSource(this)
        settings = SettingValues()
        loadSettings()

        val roles = RoleService(this, settings)
        val border = BorderSetup(settings)
        game = GameService(this, settings, roles, border, Scatter(settings))

        val combat = CombatLog()
        game.registerHook(object : GameLifecycleListener {
            override fun onGameEnd() = combat.clear()
        })

        val shrink = BorderShrinkService(this, settings, border)
        game.registerHook(shrink)

        // 부활 위치는 "축소가 끝날 크기" 기준으로 잡는다.
        val ground = SafeGround(settings)
        ground.safeHalfSize = { world ->
            minOf(world.worldBorder.size, shrink.targetSizeIn(world) ?: Double.MAX_VALUE) / 2.0
        }

        val holds = RespawnHoldService(this, game, roles, ground, settings)
        game.registerHook(holds)

        val rescue = BorderRescueService(this, game, shrink, ground, holds, combat, settings)
        game.registerHook(rescue)

        val tracker = TrackerService(this, game, holds, settings)
        game.registerHook(tracker)
        game.registerHook(ProximityAlarm(this, game, holds, rescue, settings))

        if (server.pluginManager.isPluginEnabled("ProtocolLib")) {
            val glow = PacketGlow(this)
            tracker.glow = glow
            game.registerHook(glow)
            closeables += glow
        } else {
            logger.warning("ProtocolLib 이 없어 추적기 발광을 끕니다.")
        }

        val tabHider = TabHider(this, settings)
        game.registerHook(tabHider)

        val nameTags = NameTagHider(game, settings)
        game.registerHook(nameTags)

        val hud = HudService(this, game, shrink, nameTags, settings)
        game.registerHook(hud)

        val teleports = TeamTeleportService(this, game, holds, settings)
        game.registerHook(teleports)

        saves = SaveService(this, game, border, shrink, settings)
        game.registerHook(saves)

        registerListeners(
            PresenceListener(game, roles, holds),
            CombatListener(this, game, combat, settings),
            DeathListener(this, game, roles, holds, ground, combat, settings),
            RulesListener(game, settings),
            holds,
            tracker,
            tabHider,
            teleports,
            hud,
        )

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val command = OuroCommand(this, game, saves, teleports)
            event.registrar().register(command.build(), "꼬리잡기", listOf("꼬리잡기", "ct"))
            command.teleportCommands().forEach { event.registrar().register(it, "팀원 이동") }
        }

        if (saves.hasSave) logger.info("저장된 게임이 있습니다. /ouro resume 으로 이어서 진행할 수 있습니다.")
        logger.info("Ouroboros ${pluginMeta.version} 활성화")
    }

    override fun onDisable() {
        when {
            ::saves.isInitialized && game.isRunning -> saves.suspend()
            ::game.isInitialized -> game.stop(announce = false)
        }
        closeables.forEach { it.close() }
        logger.info("Ouroboros 비활성화")
    }

    /** 시작할 때와 /ouro reload 에서 쓴다. */
    fun loadSettings() {
        reloadConfig()
        config.options().setHeader(
            listOf(
                "Ouroboros 설정",
                "대부분의 값은 /ouro set <키> <값> 으로 게임 안에서도 바꿀 수 있습니다.",
                "새 버전에서 생긴 항목은 서버를 켤 때 자동으로 추가됩니다.",
            )
        )

        val report = settings.load(configSource)
        report.warnings.forEach { logger.warning(it) }
        if (report.dirty) {
            saveConfig()
            logger.info("설정 파일에 새 항목 ${report.added.size}개를 추가했습니다.")
        }
    }

    private fun registerListeners(vararg listeners: Listener) {
        listeners.forEach { server.pluginManager.registerEvents(it, this) }
    }
}