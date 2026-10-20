package numunun.ouroboros.core

import java.util.UUID

/** 게임 안에서의 역할 */
enum class Role {
    /** 색의 주인. 이 사람이 사냥당하면 팀 전체가 복속된다. */
    OWNER,

    /** 복속된 노예. 죽어도 팀은 넘어가지 않는다. */
    SLAVE,

    /** 게임 도중 처음 들어온 관전자 */
    SPECTATOR,

    /** 게임과 무관 */
    NONE,
}

/** 공격자 입장에서 본 피해자와의 관계 */
enum class Relation {
    SAME_TEAM,

    /** 피해자가 내 목표 팀 → 공격 가능 */
    PREY,

    /** 피해자가 나를 쫓는 팀 → 공격 불가, 넉백만 */
    HUNTER,

    /** 사슬상 무관하거나, 둘 중 하나가 휴면 팀 */
    UNRELATED,

    /** 둘 중 하나가 참가자가 아님 */
    OUT_OF_GAME,
}

/** 사망 판정 결과 */
enum class DeathVerdict {
    /** 헌터가 목표 색의 주인을 잡았다 → 복속 */
    CAPTURE,

    /** 헌터가 목표 팀의 노예를 잡았다 → 복속 없음, 즉시 부활 */
    HUNTED,

    /** 그 외 모든 죽음 → 부활 유예 */
    NORMAL,
}

/** 어떤 색의 목표가 바뀌었다는 기록. 해당 팀에게만 알려 주는 데 쓴다. */
data class TargetChange(
    val hunter: ChainColor,
    val before: ChainColor?,
    val after: ChainColor?,
)

/** 복속 결과 */
data class AbsorbResult(
    val hunter: ChainColor,
    val prey: ChainColor,
    /** 새로 노예가 된 사람들 (먹힌 팀의 주인 + 노예 전원) */
    val captured: List<UUID>,
    /** 이 복속으로 목표가 바뀐 색들 */
    val changes: List<TargetChange>,
)

/**
 * 하나의 색 팀.
 *
 * @param ownerId 게임 시작 때 이 색을 받은 사람. 끝까지 바뀌지 않는다.
 */
class ColorTeam(
    val color: ChainColor,
    val ownerId: UUID,
) {
    val slaves: MutableSet<UUID> = LinkedHashSet()

    /** 주인 + 노예 전원 */
    val members: List<UUID>
        get() = buildList(slaves.size + 1) {
            add(ownerId)
            addAll(slaves)
        }

    operator fun contains(uuid: UUID): Boolean = uuid == ownerId || uuid in slaves
}