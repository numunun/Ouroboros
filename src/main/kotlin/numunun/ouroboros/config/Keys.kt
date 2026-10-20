package numunun.ouroboros.config

import numunun.ouroboros.config.SettingType.BoolType
import numunun.ouroboros.config.SettingType.DoubleType
import numunun.ouroboros.config.SettingType.IntType
import numunun.ouroboros.config.SettingType.KeyType
import numunun.ouroboros.config.SettingType.TextType

/**
 * 모든 설정 항목. 여기에 선언한 순서대로 config.yml 에 쓰인다.
 *
 * 설정 파일은 이 목록에서 자동으로 만들어진다. jar 안에 config.yml 을 따로 두지 않으므로
 * 코드와 파일 내용이 어긋날 일이 없고, 새 항목은 다음 실행 때 주석과 함께 파일에 추가된다.
 */
object Keys {

    private val registry = LinkedHashMap<String, Setting<*>>()

    val ALL: Collection<Setting<*>> get() = registry.values

    fun byPath(path: String): Setting<*>? = registry[path]

    private fun <T : Any> define(path: String, type: SettingType<T>, default: T, description: String): Setting<T> {
        require(path !in registry) { "중복된 설정 경로: $path" }
        return Setting(path, type, default, description).also { registry[path] = it }
    }

    // ── 부활 ──────────────────────────────────────────────

    val RESPAWN_DELAY = define(
        "respawn.delay-seconds", IntType(0..600), 15,
        "헌터가 아닌 원인으로 죽었을 때의 부활 유예(초)",
    )
    val REJOIN_PENALTY = define(
        "respawn.rejoin-penalty-seconds", IntType(0..600), 180,
        "게임 도중 나갔다 들어온 참가자의 부활 유예(초)",
    )
    val RESPAWN_PROTECTION = define(
        "respawn.protection-seconds", IntType(0..300), 30,
        "부활 직후 수중 호흡과 화염 저항 지속 시간(초)",
    )

    // ── 접속 ──────────────────────────────────────────────

    val FORFEIT_MINUTES = define(
        "presence.forfeit-minutes", IntType(0..1440), 10,
        "주인이 이만큼 접속하지 않으면 그 색을 앞 팀에 넘긴다(분). 0 이면 넘기지 않는다",
    )

    // ── 전투 ──────────────────────────────────────────────

    val KILL_CREDIT_SECONDS = define(
        "combat.kill-credit-seconds", IntType(0..60), 8,
        "직접 죽이지 않은 죽음(낙사 등)을 마지막 정당한 타격자의 킬로 인정하는 시간(초)",
    )
    val HUNTER_KNOCKBACK = define(
        "combat.hunter-knockback", DoubleType(0.0, 5.0), 1.0,
        "사냥감이 헌터를 때리면 피해 대신 헌터가 밀려나는 세기. 1 이면 일반 공격과 같다. 0 이면 끈다",
    )

    // ── 접근 경보 ─────────────────────────────────────────

    val ALARM_BOX_RANGE = define(
        "alarm.box-range", DoubleType(1.0, 128.0), 20.0,
        "헌터가 각 축으로 이 거리 안이면 심장이 빨리 뛰고, 추적기를 쓰면 목표가 발광한다",
    )
    val ALARM_SOUND_RANGE = define(
        "alarm.sound-range", DoubleType(0.0, 256.0), 40.0,
        "헌터가 각 축으로 이 거리 안이면 [ ❤ ] 경보와 심장 소리. 가까울수록 커진다",
    )
    val ALARM_SYMBOL = define(
        "alarm.symbol", TextType(1..8), "❤",
        "경보에 쓰는 글자. 깨져 보이면 ♥ ✚ ● 등으로 바꿔 본다",
    )

    // ── 추적기 ────────────────────────────────────────────

    val TRACKER_ITEM = define(
        "tracker.item", KeyType(listOf("minecraft:diamond", "minecraft:emerald", "minecraft:compass")),
        "minecraft:diamond",
        "우클릭하면 추적기가 되는 아이템",
    )
    val TRACKER_PARTICLE = define(
        "tracker.particle", KeyType(listOf("minecraft:electric_spark", "minecraft:end_rod", "minecraft:wax_off")),
        "minecraft:electric_spark",
        "추적 경로에 뿌리는 파티클",
    )
    val TRACKER_TRAIL_SECONDS = define(
        "tracker.trail-seconds", IntType(1..120), 20,
        "추적 파티클 지속 시간(초)",
    )
    val TRACKER_GLOW_SECONDS = define(
        "tracker.glow-seconds", IntType(0..60), 10,
        "경보 거리 안에서 추적기를 쓰면 목표가 사용자에게만 발광하는 시간(초). 0 이면 끈다",
    )

    // ── 노예 ──────────────────────────────────────────────

    val SLAVE_MAX_HEARTS = define(
        "slave.max-hearts", IntType(1..10), 6,
        "노예의 최대 체력(하트 수)",
    )
    val SLAVE_ATTACK_MULTIPLIER = define(
        "slave.attack-multiplier", DoubleType(0.0, 1.0), 0.6,
        "노예의 공격력 배율",
    )

    // ── 월드보더 ──────────────────────────────────────────

    val BORDER_ENABLED = define(
        "border.enabled", BoolType, true,
        "게임 중 월드보더를 적용한다",
    )
    val BORDER_SIZE = define(
        "border.size", DoubleType(16.0, 59_999_968.0), 20_000.0,
        "오버월드 기준 보더 지름",
    )
    val BORDER_CENTER_X = define(
        "border.center-x", DoubleType(-29_999_984.0, 29_999_984.0), 0.0,
        "보더 중심 X",
    )
    val BORDER_CENTER_Z = define(
        "border.center-z", DoubleType(-29_999_984.0, 29_999_984.0), 0.0,
        "보더 중심 Z",
    )
    val BORDER_NETHER_SCALED = define(
        "border.nether-scaled", BoolType, true,
        "네더 보더를 1/8 로 줄여 오버월드와 실제 이동 범위를 맞춘다",
    )
    val SHRINK_ENABLED = define(
        "border.shrink.enabled", BoolType, true,
        "단계별 보더 축소를 사용한다. 단계는 border.shrink.phases 에서 정한다",
    )

    // ── 보더 구조 ─────────────────────────────────────────

    val RESCUE_ENABLED = define(
        "rescue.enabled", BoolType, true,
        "보더에 갇힌 사람을 자동으로 옮겨 준다",
    )
    val RESCUE_TRIGGER_DISTANCE = define(
        "rescue.trigger-distance", DoubleType(1.0, 128.0), 16.0,
        "축소 중 보더 끝에서 이 거리 안의 지하에 있으면 같은 X,Z 의 지상으로 올린다",
    )
    val RESCUE_DELAY = define(
        "rescue.delay-seconds", IntType(0..30), 3,
        "옮기기 전 카운트다운(초). 그 사이 스스로 벗어나면 취소된다",
    )
    val RESCUE_MARGIN = define(
        "rescue.margin", DoubleType(0.0, 256.0), 16.0,
        "보더 밖에서 안으로 옮기거나 부활시킬 때 보더 끝에서 띄우는 거리",
    )
    val RESCUE_COMBAT_LOCK = define(
        "rescue.combat-lock-seconds", IntType(0..60), 10,
        "교전 직후 이 시간 동안은 옮기지 않는다(초). 도주 수단 방지",
    )

    // ── 주인-노예 이동 ────────────────────────────────────

    val TPA_DELAY = define(
        "tpa.delay-seconds", IntType(0..60), 15,
        "이동 요청 수락 후 실제 이동까지(초)",
    )
    val TPA_SAFE_RADIUS = define(
        "tpa.safe-radius", DoubleType(0.0, 128.0), 30.0,
        "보내는 쪽 근처 헌터, 받는 쪽 근처 사냥감을 확인하는 반경",
    )

    // ── 진행 ──────────────────────────────────────────────

    val SCATTER_ON_START = define(
        "start.scatter", BoolType, true,
        "시작할 때 참가자를 보더 안에 흩어 놓는다",
    )
    val AUTO_SAVE_MINUTES = define(
        "save.auto-save-minutes", IntType(0..120), 5,
        "진행 상황 자동 저장 주기(분). 0 이면 끈다",
    )

    // ── 표시와 규칙 ───────────────────────────────────────

    val CORPSE = define(
        "display.corpse", BoolType, true,
        "부활 유예 동안 그 자리에 누운 모습으로 남긴다. false 면 숨긴다",
    )
    val HIDE_TAB_LIST = define(
        "display.hide-tab-list", BoolType, true,
        "게임 중 탭 목록에서 다른 사람을 숨긴다",
    )
    val DISABLE_CHAT = define(
        "rules.disable-chat", BoolType, true,
        "게임 중 채팅을 막는다. 명령어는 쓸 수 있다",
    )
    val BLOCK_THE_END = define(
        "rules.block-the-end", BoolType, true,
        "엔드로 이동을 막는다",
    )
    val FORCE_KEEP_INVENTORY = define(
        "rules.force-keep-inventory", BoolType, true,
        "게임 중 인벤토리 보존을 강제하고 사망 메시지를 숨긴다. 종료 시 원래대로",
    )
    val HIDE_NAMETAGS = define(
        "display.hide-nametags", BoolType, true,
        "게임 중 머리 위 이름을 숨긴다",
    )
    val HUD = define(
        "display.hud", BoolType, true,
        "오른쪽 사이드바(내 팀·목표 팀·전체 현황)와 위쪽 보스바(보더·이탈 시간)를 띄운다",
    )
    val HIDE_LOCATOR_BAR = define(
        "display.hide-locator-bar", BoolType, true,
        "게임 중 경험치바 위 위치 표시줄에 플레이어가 안 뜨게 한다. 주인·노예 방향은 사이드바에 나온다",
    )
}