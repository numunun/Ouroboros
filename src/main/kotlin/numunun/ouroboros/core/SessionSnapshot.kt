package numunun.ouroboros.core

import java.util.UUID

data class TeamSnapshot(val color: ChainColor, val owner: UUID, val slaves: List<UUID>)

data class ShrinkSnapshot(val phase: Int, val stage: ShrinkSchedule.Stage, val elapsed: Int)

data class BorderSnapshot(val centerX: Double, val centerZ: Double, val size: Double)

/** 서버를 껐다 켜도 이어 갈 수 있도록 저장하는 한 판의 상태 */
data class SessionSnapshot(
    val participants: Set<UUID>,
    val spectators: Set<UUID>,
    val teams: List<TeamSnapshot>,
    val dormantMillis: Map<ChainColor, Long>,
    val shrink: ShrinkSnapshot?,
    /** 저장 시점의 오버월드 기준 보더 지름 */
    val borderSize: Double?,
    /** 게임 전 원래 보더. 끝날 때 되돌리기용 */
    val borderOriginals: Map<UUID, BorderSnapshot>,
) {
    /** 휴면 상태는 저장하지 않는다. 불러온 뒤 접속 상태로 다시 계산한다. */
    fun toSession(): GameSession {
        val session = GameSession(
            participants,
            teams.map { team -> ColorTeam(team.color, team.owner).apply { slaves.addAll(team.slaves) } },
        )
        spectators.forEach { session.addSpectator(it) }
        return session
    }
}

fun GameSession.teamSnapshots(): List<TeamSnapshot> =
    allTeams.map { TeamSnapshot(it.color, it.ownerId, it.slaves.toList()) }