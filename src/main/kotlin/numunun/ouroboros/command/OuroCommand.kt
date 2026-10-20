package numunun.ouroboros.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import numunun.ouroboros.Ouroboros
import numunun.ouroboros.config.Keys
import numunun.ouroboros.core.Role
import numunun.ouroboros.game.GameService
import numunun.ouroboros.game.SaveService
import numunun.ouroboros.game.StartResult
import numunun.ouroboros.game.TeamTeleportService
import numunun.ouroboros.ui.Messages
import numunun.ouroboros.ui.label
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * /ouro (별칭 /꼬리잡기, /ct)
 *
 * 누구나: info, tpa <팀원>, tpaccept, tpdeny
 * 관리자: start, stop, status, save, resume, reload, set [키] [값]
 */
class OuroCommand(
    private val plugin: Ouroboros,
    private val game: GameService,
    private val saves: SaveService,
    private val teleports: TeamTeleportService,
) {

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("ouro")
            .executes(exec { help(it) })
            .then(Commands.literal("info").executes(exec { info(it) }))
            .then(admin("start") { start(it) })
            .then(admin("stop") { stop(it) })
            .then(admin("status") { status(it) })
            .then(admin("save") { save(it) })
            .then(admin("resume") { resume(it) })
            .then(admin("reload") { reload(it) })
            .then(setNode())
            .build()

    // ── 트리 ──────────────────────────────────────────────

    private fun tpaNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("tpa").then(
            Commands.argument("teammate", StringArgumentType.word())
                .suggests { ctx, builder ->
                    (ctx.source.sender as? Player)?.let { teleports.teammateNames(it) }.orEmpty()
                        .filter { it.lowercase().startsWith(builder.remainingLowerCase) }
                        .forEach { builder.suggest(it) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val player = ctx.source.sender as? Player
                    if (player == null) {
                        ctx.source.sender.sendMessage(Messages.warn("플레이어만 쓸 수 있습니다."))
                    } else {
                        teleports.request(player, StringArgumentType.getString(ctx, "teammate"))
                    }
                    Command.SINGLE_SUCCESS
                }
        )

    /** /tpa <팀원>, /tpaccept, /tpdeny */
    fun teleportCommands(): List<LiteralCommandNode<CommandSourceStack>> = listOf(
        tpaNode().build(),
        Commands.literal("tpaccept").executes(execPlayer { teleports.accept(it) }).build(),
        Commands.literal("tpdeny").executes(execPlayer { teleports.deny(it) }).build(),
    )

    private fun setNode(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("set")
            .requires { isAdmin(it) }
            .executes(exec { listSettings(it) })
            .then(
                Commands.argument("key", StringArgumentType.word())
                    .suggests { _, builder ->
                        Keys.ALL.map { it.path }
                            .filter { it.startsWith(builder.remainingLowerCase) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        describe(ctx.source.sender, StringArgumentType.getString(ctx, "key"))
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("value", StringArgumentType.greedyString())
                            .suggests { ctx, builder ->
                                val setting = Keys.byPath(StringArgumentType.getString(ctx, "key"))
                                setting?.let { plugin.settings.suggestions(it) }.orEmpty()
                                    .filter { it.startsWith(builder.remaining) }
                                    .forEach { builder.suggest(it) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                set(
                                    ctx.source.sender,
                                    StringArgumentType.getString(ctx, "key"),
                                    StringArgumentType.getString(ctx, "value"),
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
            )

    // ── 동작 ──────────────────────────────────────────────

    private fun help(sender: CommandSender) {
        sender.sendMessage(Messages.info("/ouro info - 내 색과 목표"))
        sender.sendMessage(Messages.info("/tpa <팀원> - 노예가 팀원에게 이동 요청"))
        if (sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(Messages.info("/ouro start | stop | status | save | resume | reload"))
            sender.sendMessage(Messages.info("/ouro set [키] [값] - 설정 보기·바꾸기"))
        }
    }

    private fun start(sender: CommandSender) {
        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.gameMode != GameMode.SPECTATOR && it.hasPermission(PERM_PLAY) }
        when (val result = game.start(candidates)) {
            StartResult.Started -> Unit
            is StartResult.Rejected -> sender.sendMessage(Messages.warn(result.reason))
        }
    }

    private fun stop(sender: CommandSender) {
        if (!game.isRunning) {
            sender.sendMessage(Messages.warn("진행 중인 게임이 없습니다."))
            return
        }
        saves.save()
        game.stop()
        sender.sendMessage(Messages.info("저장해 두었습니다. /ouro resume 으로 이어 갈 수 있습니다."))
    }

    private fun save(sender: CommandSender) {
        sender.sendMessage(
            if (saves.save()) Messages.good("진행 상황을 저장했습니다.") else Messages.warn("진행 중인 게임이 없습니다.")
        )
    }

    private fun resume(sender: CommandSender) {
        saves.resume()?.let { sender.sendMessage(Messages.warn(it)) }
    }

    private fun reload(sender: CommandSender) {
        plugin.loadSettings()
        game.reapplyRoles()
        sender.sendMessage(Messages.good("설정을 다시 불러왔습니다."))
    }

    private fun listSettings(sender: CommandSender) {
        Keys.ALL.forEach {
            sender.sendMessage(Component.text(" ${it.path} = ${plugin.settings.formatted(it)}", NamedTextColor.GRAY))
        }
    }

    private fun describe(sender: CommandSender, key: String) {
        val setting = Keys.byPath(key) ?: return sender.sendMessage(Messages.warn("없는 설정입니다: $key"))
        sender.sendMessage(Messages.info("$key = ${plugin.settings.formatted(setting)}"))
        sender.sendMessage(Messages.line(Messages.gray(setting.description)))
        setting.type.rangeText?.let { sender.sendMessage(Messages.line(Messages.gray("허용: $it"))) }
    }

    private fun set(sender: CommandSender, key: String, raw: String) {
        plugin.settings.setFromInput(plugin.configSource, key, raw)
            .onSuccess { value ->
                plugin.saveConfig()
                sender.sendMessage(Messages.good("$key = $value"))
                if (game.isRunning && key.startsWith("border.")) {
                    sender.sendMessage(Messages.info("보더 설정은 다음 판부터 적용됩니다."))
                }
            }
            .onFailure { sender.sendMessage(Messages.warn(it.message ?: "바꿀 수 없습니다.")) }
    }

    /** 관리자용. 사슬 전체와 주인, 노예, 휴면 상태가 다 보인다. */
    private fun status(sender: CommandSender) {
        val session = game.session ?: run {
            sender.sendMessage(Messages.info("진행 중인 게임이 없습니다."))
            return
        }
        sender.sendMessage(Messages.chain(session.aliveColors))
        session.aliveColors.forEach { color ->
            val team = session.team(color) ?: return@forEach
            val owner = Bukkit.getOfflinePlayer(team.ownerId).name ?: "?"
            val slaves = team.slaves.mapNotNull { Bukkit.getOfflinePlayer(it).name }

            val line = Component.text()
                .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                .append(color.label)
                .append(Component.text(" 주인 $owner", NamedTextColor.WHITE))
            if (session.isDormant(color)) {
                val minutes = game.dormantMinutes(color) ?: 0
                line.append(Component.text(" [휴면 ${minutes}분]", NamedTextColor.DARK_GRAY))
            }
            if (slaves.isNotEmpty()) {
                line.append(Component.text(" / 노예 ${slaves.joinToString(", ")}", NamedTextColor.GRAY))
            }
            sender.sendMessage(line.build())
        }
    }

    private fun info(sender: CommandSender) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Messages.warn("플레이어만 쓸 수 있습니다."))
            return
        }
        if (!game.isRunning) {
            player.sendMessage(Messages.info("진행 중인 게임이 없습니다."))
            return
        }
        val color = game.colorOf(player) ?: run {
            player.sendMessage(Messages.info("이번 판 참가자가 아닙니다."))
            return
        }
        val role = when (game.roleOf(player)) {
            Role.OWNER -> "색의 주인"
            Role.SLAVE -> "노예"
            else -> "관전자"
        }
        player.sendMessage(Messages.yourColor(color))
        player.sendMessage(Messages.info("역할: $role"))
        player.sendMessage(Messages.currentTarget(game.targetOf(player)))
    }

    // ── 도우미 ────────────────────────────────────────────

    private fun isAdmin(source: CommandSourceStack): Boolean = source.sender.hasPermission(PERM_ADMIN)

    private fun exec(action: (CommandSender) -> Unit) = Command<CommandSourceStack> { ctx ->
        action(ctx.source.sender)
        Command.SINGLE_SUCCESS
    }

    private fun execPlayer(action: (Player) -> Unit) = Command<CommandSourceStack> { ctx ->
        val player = ctx.source.sender as? Player
        if (player == null) ctx.source.sender.sendMessage(Messages.warn("플레이어만 쓸 수 있습니다.")) else action(player)
        Command.SINGLE_SUCCESS
    }

    private fun admin(name: String, action: (CommandSender) -> Unit): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires { isAdmin(it) }.executes(exec(action))

    companion object {
        const val PERM_ADMIN = "ouroboros.admin"
        const val PERM_PLAY = "ouroboros.play"
    }
}