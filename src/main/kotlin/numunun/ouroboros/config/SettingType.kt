package numunun.ouroboros.config

import java.util.Locale

/**
 * 설정값 하나의 타입. 파일에서 읽기, 명령어 입력 해석, 파일에 쓰기를 한곳에서 정한다.
 * Bukkit 을 모르므로 서버 없이 테스트할 수 있다.
 */
sealed class SettingType<T : Any> {

    /** 명령어로 들어온 문자열을 해석한다. 실패하면 사람이 읽을 이유를 담는다. */
    abstract fun parse(raw: String): Result<T>

    /** 설정 파일에서 읽은 값을 해석한다. 타입이 안 맞으면 null. */
    abstract fun fromFile(value: Any?): T?

    /** 설정 파일에 쓸 값 */
    open fun toFile(value: T): Any = value

    /** 사람이 읽는 표기 */
    open fun format(value: T): String = value.toString()

    /** 허용 범위 안내. 없으면 null */
    open val rangeText: String? = null

    /** 탭 완성 후보 */
    open fun suggestions(current: T): List<String> = listOf(format(current))

    protected fun fail(message: String): Result<T> = Result.failure(IllegalArgumentException(message))

    class IntType(private val range: IntRange) : SettingType<Int>() {
        override fun parse(raw: String): Result<Int> {
            val number = raw.trim().toIntOrNull() ?: return fail("정수가 아닙니다: $raw")
            return check(number)
        }

        override fun fromFile(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Double -> value.takeIf { it % 1.0 == 0.0 }?.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }?.takeIf { it in range }

        override val rangeText: String = "${range.first} ~ ${range.last}"

        override fun suggestions(current: Int): List<String> =
            listOf(current, range.first, range.last).distinct().map { it.toString() }

        private fun check(number: Int): Result<Int> =
            if (number in range) Result.success(number) else fail("범위를 벗어났습니다: $number ($rangeText)")
    }

    class DoubleType(private val min: Double, private val max: Double) : SettingType<Double>() {
        override fun parse(raw: String): Result<Double> {
            val number = raw.trim().toDoubleOrNull()
            if (number == null || !number.isFinite()) return fail("숫자가 아닙니다: $raw")
            return if (number in min..max) Result.success(number) else fail("범위를 벗어났습니다: $raw ($rangeText)")
        }

        override fun fromFile(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() && it in min..max }

        override fun format(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

        override val rangeText: String = "${format(min)} ~ ${format(max)}"
    }

    data object BoolType : SettingType<Boolean>() {
        private val truthy = setOf("true", "on", "yes", "켜기", "참")
        private val falsy = setOf("false", "off", "no", "끄기", "거짓")

        override fun parse(raw: String): Result<Boolean> {
            val key = raw.trim().lowercase(Locale.ROOT)
            return when (key) {
                in truthy -> Result.success(true)
                in falsy -> Result.success(false)
                else -> fail("true 또는 false 로 입력하세요: $raw")
            }
        }

        override fun fromFile(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is String -> parse(value).getOrNull()
            else -> null
        }

        override fun suggestions(current: Boolean): List<String> = listOf("true", "false")
    }

    class TextType(private val lengths: IntRange) : SettingType<String>() {
        override fun parse(raw: String): Result<String> {
            val length = raw.codePointCount(0, raw.length)
            return if (length in lengths) Result.success(raw) else fail("길이는 $rangeText 글자여야 합니다")
        }

        override fun fromFile(value: Any?): String? =
            (value as? String)?.let { parse(it).getOrNull() }

        override val rangeText: String = "${lengths.first} ~ ${lengths.last}"
    }

    /**
     * 마인크래프트 리소스 키 (예: minecraft:diamond).
     * 실제로 존재하는 아이템/파티클인지는 서버 쪽에서 레지스트리로 확인한다.
     */
    class KeyType(private val examples: List<String> = emptyList()) : SettingType<String>() {
        override fun parse(raw: String): Result<String> {
            val normalized = normalize(raw) ?: return fail("올바른 키가 아닙니다: $raw (예: minecraft:diamond)")
            return Result.success(normalized)
        }

        override fun fromFile(value: Any?): String? = (value as? String)?.let { normalize(it) }

        override fun suggestions(current: String): List<String> = (listOf(current) + examples).distinct()

        companion object {
            private val PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

            /** 대문자, 네임스페이스 생략을 허용한다. DIAMOND → minecraft:diamond */
            fun normalize(raw: String): String? {
                val lower = raw.trim().lowercase(Locale.ROOT)
                val full = if (':' in lower) lower else "minecraft:$lower"
                return full.takeIf { PATTERN.matches(it) }
            }
        }
    }
}