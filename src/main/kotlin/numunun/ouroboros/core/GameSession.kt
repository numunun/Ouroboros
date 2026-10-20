package numunun.ouroboros.core

import java.util.UUID

/**
 * 한 판의 상태와 규칙. 부작용(메시지, 텔레포트 등)은 하나도 없다.
 *
 * ## 휴면(dormant)
 * 주인이 접속해 있지 않은 색은 사슬에서 건너뛴다.
 * - 그 색을 쫓던 헌터의 목표는 즉시 다음 활동 색으로 넘어간다.
 * - 휴면 팀은 아무도 공격할 수 없고, 아무에게도 공격받지 않는다.
 *   (주인만 빠지고 노예끼리 사냥하는 꼼수 방지)
 * - 주인이 돌아오면 원래 자리로 복귀한다.
 * 승리 판정은 휴면과 무관하게 살아 있는 색 수로만 한다.
 */
class GameSession(
    /** 게임 시작 시점의 참가자 전원 */
    val participants: Set<UUID>,
    teams: List<ColorTeam>,
) {
    init {
        require(teams.map { it.color }.toSet().size == teams.size) { "같은 색 팀이 두 개 있다" }
        val everyone = teams.flatMap { it.members }
        require(everyone.toSet().size == everyone.size) { "한 사람이 두 팀에 들어가 있다" }
    }

    private val teamsByColor = LinkedHashMap<ChainColor, ColorTeam>().apply {
        teams.sortedBy { it.color.ordinal }.forEach { put(it.color, it) }
    }

    /** 살아 있는 색. 사슬 순서를 유지하며 원형으로 순환한다. */
    private val ring: MutableList<ChainColor> = teamsByColor.keys.toMutableList()

    /** 플레이어 → 소속 색. 노예도 들어 있다. */
    private val membership = HashMap<UUID, ChainColor>().apply {
        teams.forEach { team -> team.members.forEach { put(it, team.color) } }
    }

    private val dormant = HashSet<ChainColor>()

    private val spectatorSet = LinkedHashSet<UUID>()

    // ── 조회 ──────────────────────────────────────────────

    val aliveColors: List<ChainColor> get() = ring.toList()

    val aliveTeamCount: Int get() = ring.size

    /** 휴면이 아닌 색 */
    val activeColors: List<ChainColor> get() = ring.filter { it !in dormant }

    val allTeams: Collection<ColorTeam> get() = teamsByColor.values

    val spectators: Set<UUID> get() = spectatorSet

    /** 살아 남은 색이 하나면 그 색 */
    val winner: ChainColor? get() = ring.singleOrNull()

    fun team(color: ChainColor): ColorTeam? = teamsByColor[color]

    fun colorOf(uuid: UUID): ChainColor? = membership[uuid]

    fun teamOf(uuid: UUID): ColorTeam? = membership[uuid]?.let { teamsByColor[it] }

    /** 색 팀 소속인가 (주인이든 노예든) */
    fun isMember(uuid: UUID): Boolean = uuid in membership

    fun isDormant(color: ChainColor): Boolean = color in dormant

    fun roleOf(uuid: UUID): Role = when {
        uuid in spectatorSet -> Role.SPECTATOR
        teamOf(uuid)?.ownerId == uuid -> Role.OWNER
        uuid in membership -> Role.SLAVE
        else -> Role.NONE
    }

    /** 이 색이 쫓는 색. 휴면이거나 활동 중인 상대가 없으면 null */
    fun targetOf(color: ChainColor): ChainColor? = neighbor(color, +1)

    /** 이 색을 쫓는 색. 휴면이거나 활동 중인 상대가 없으면 null */
    fun hunterOf(color: ChainColor): ChainColor? = neighbor(color, -1)

    /**
     * 휴면이 길어져 넘겨야 할 때 받을 색 = 사슬상 바로 앞의 활동 색.
     * 대상 색 자신은 휴면이어도 된다.
     */
    fun heirOf(color: ChainColor): ChainColor? {
        val index = ring.indexOf(color)
        if (index < 0) return null
        return (1 until ring.size)
            .map { ring[Math.floorMod(index - it, ring.size)] }
            .firstOrNull { it !in dormant }
    }

    fun relation(attacker: UUID, victim: UUID): Relation {
        val attackerColor = membership[attacker] ?: return Relation.OUT_OF_GAME
        val victimColor = membership[victim] ?: return Relation.OUT_OF_GAME
        if (attackerColor == victimColor) return Relation.SAME_TEAM
        // 둘만 남으면 서로가 목표이자 헌터다. 공격 가능이 우선이다.
        if (targetOf(attackerColor) == victimColor) return Relation.PREY
        if (hunterOf(attackerColor) == victimColor) return Relation.HUNTER
        return Relation.UNRELATED
    }

    /**
     * 사망 판정. 이 함수 하나가 "복속인가, 즉시 부활인가, 유예인가"를 정한다.
     *
     * @param killer 실제로 죽인 사람. 확실하지 않으면 null 을 넘겨야 한다.
     */
    fun judgeDeath(victim: UUID, killer: UUID?): DeathVerdict {
        if (killer == null || killer == victim) return DeathVerdict.NORMAL
        if (relation(killer, victim) != Relation.PREY) return DeathVerdict.NORMAL
        val preyTeam = teamOf(victim) ?: return DeathVerdict.NORMAL
        return if (preyTeam.ownerId == victim) DeathVerdict.CAPTURE else DeathVerdict.HUNTED
    }

    // ── 변경 ──────────────────────────────────────────────

    /**
     * 접속 상태를 반영한다. 주인이 오프라인인 색은 휴면.
     *
     * @return 목표가 바뀐 색들
     */
    fun updatePresence(isOnline: (UUID) -> Boolean): List<TargetChange> {
        val before = snapshotTargets()
        ring.forEach { color ->
            val owner = teamsByColor.getValue(color).ownerId
            if (isOnline(owner)) dormant.remove(color) else dormant.add(color)
        }
        return diffTargets(before)
    }

    /**
     * [prey] 팀 전체를 [hunter] 팀의 노예로 만든다.
     *
     * 사냥이든 휴면 상속이든 여기로 모인다. 자격 검사는 호출하는 쪽 책임이다.
     */
    fun absorb(hunter: ChainColor, prey: ChainColor): AbsorbResult {
        require(hunter != prey) { "자기 자신을 복속할 수 없다: $hunter" }
        val hunterTeam = requireNotNull(teamsByColor[hunter]) { "이미 사라진 색: $hunter" }
        val preyTeam = requireNotNull(teamsByColor[prey]) { "이미 사라진 색: $prey" }

        val before = snapshotTargets()

        val captured = preyTeam.members
        captured.forEach {
            hunterTeam.slaves.add(it)
            membership[it] = hunter
        }
        ring.remove(prey)
        dormant.remove(prey)
        teamsByColor.remove(prey)

        return AbsorbResult(hunter, prey, captured, diffTargets(before))
    }

    fun addSpectator(uuid: UUID) {
        require(uuid !in membership) { "참가자는 관전자가 될 수 없다" }
        spectatorSet.add(uuid)
    }

    // ── 내부 ──────────────────────────────────────────────

    /** step 방향으로 가장 가까운 활동 색 */
    private fun neighbor(color: ChainColor, step: Int): ChainColor? {
        if (color in dormant) return null
        val index = ring.indexOf(color)
        if (index < 0) return null
        return (1 until ring.size)
            .map { ring[Math.floorMod(index + it * step, ring.size)] }
            .firstOrNull { it !in dormant }
    }

    private fun snapshotTargets(): Map<ChainColor, ChainColor?> =
        ring.associateWith { targetOf(it) }

    /** 전후를 비교한다. 사라진 색은 뺀다. */
    private fun diffTargets(before: Map<ChainColor, ChainColor?>): List<TargetChange> =
        ring.mapNotNull { color ->
            val after = targetOf(color)
            val previous = before[color]
            if (previous != after) TargetChange(color, previous, after) else null
        }
}