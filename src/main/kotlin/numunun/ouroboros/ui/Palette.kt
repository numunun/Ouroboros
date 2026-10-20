package numunun.ouroboros.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import numunun.ouroboros.core.ChainColor
import java.util.Locale

/** core 의 색을 화면 색으로. enum 이름이 마인크래프트 색 이름과 같아서 이름으로 찾는다. */
val ChainColor.textColor: NamedTextColor
    get() = NamedTextColor.NAMES.value(name.lowercase(Locale.ROOT)) ?: NamedTextColor.WHITE

/** "■" 한 칸 */
val ChainColor.swatch: Component
    get() = Component.text("■", textColor)

/** 색 이름을 그 색으로 */
val ChainColor.label: Component
    get() = Component.text(korean, textColor)