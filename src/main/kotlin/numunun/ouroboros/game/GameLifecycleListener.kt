package numunun.ouroboros.game

import numunun.ouroboros.core.GameSession

/** 게임 흐름에 반응해야 하는 구성요소가 구현한다. */
interface GameLifecycleListener {
    fun onGameStart(session: GameSession) {}

    /** 복속, 휴면 등으로 소속이나 목표가 바뀌었을 때 */
    fun onSessionChanged(session: GameSession) {}

    fun onGameEnd() {}
}