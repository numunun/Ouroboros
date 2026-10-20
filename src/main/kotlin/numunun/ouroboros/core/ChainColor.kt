package numunun.ouroboros.core

/**
 * 먹이사슬에 쓰는 색.
 *
 * 선언 순서가 곧 사슬 순서다. 앞의 색이 바로 뒤의 색을 쫓고, 마지막 색은 첫 색을 쫓는다.
 * 화면용 색(Adventure)은 core 밖에서 이름으로 매핑한다. core 는 Bukkit/Adventure 를 모른다.
 */
enum class ChainColor(val korean: String) {
    RED("빨강"),
    GOLD("주황"),
    YELLOW("노랑"),
    GREEN("초록"),
    DARK_GREEN("진초록"),
    AQUA("하늘"),
    DARK_AQUA("청록"),
    BLUE("파랑"),
    DARK_BLUE("남색"),
    LIGHT_PURPLE("분홍"),
    DARK_PURPLE("보라"),
    DARK_RED("자주"),
    WHITE("하양"),
    GRAY("회색"),
    DARK_GRAY("먹색"),
    BLACK("검정"),
    ;

    companion object {
        val MAX_PLAYERS: Int = entries.size

        /** 인원 수만큼 앞에서부터 잘라 온다. */
        fun take(count: Int): List<ChainColor> {
            require(count in 0..MAX_PLAYERS) { "색은 최대 ${MAX_PLAYERS}개다: $count" }
            return entries.take(count)
        }
    }
}