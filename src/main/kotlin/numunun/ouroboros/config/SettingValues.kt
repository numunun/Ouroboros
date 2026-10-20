package numunun.ouroboros.config

/** 설정 파일과의 연결. 서버에서는 Bukkit 의 FileConfiguration 이, 테스트에서는 Map 이 맡는다. */
interface ConfigSource {
    fun contains(path: String): Boolean
    fun get(path: String): Any?
    fun set(path: String, value: Any?)
    fun setComments(path: String, comments: List<String>)
}

/** 불러오기 결과 */
class LoadReport(
    /** 잘못된 값이라 기본값으로 대신한 항목들 */
    val warnings: List<String>,
    /** 파일에 없어서 새로 써 넣은 경로들 */
    val added: List<String>,
) {
    /** 파일을 저장해야 하는가 */
    val dirty: Boolean get() = added.isNotEmpty()
}

/**
 * 현재 설정값. `settings[Keys.RESPAWN_DELAY]` 처럼 타입이 보장된 채로 읽는다.
 */
class SettingValues {

    private val values = HashMap<String, Any>()

    var shrinkPhases: List<ShrinkPhase> = ShrinkPhase.DEFAULTS
        private set

    private val listeners = ArrayList<(Setting<*>) -> Unit>()

    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(setting: Setting<T>): T = values[setting.path] as T? ?: setting.default

    /** 값이 바뀌면 불린다. 진행 중인 게임에 즉시 반영해야 하는 서비스가 쓴다. */
    fun onChange(listener: (Setting<*>) -> Unit) {
        listeners += listener
    }

    /**
     * 파일에서 전부 다시 읽는다.
     * - 없는 항목은 기본값을 주석과 함께 파일에 써 넣는다.
     * - 잘못된 항목은 파일을 건드리지 않고 기본값으로 동작하며 경고를 남긴다.
     */
    fun load(source: ConfigSource): LoadReport {
        val warnings = ArrayList<String>()
        val added = ArrayList<String>()

        values.clear()
        Keys.ALL.forEach { setting ->
            if (!source.contains(setting.path)) {
                writeDefault(source, setting)
                added += setting.path
                return@forEach
            }
            val parsed = readValue(source, setting)
            if (parsed == null) {
                warnings += "${setting.path}: '${source.get(setting.path)}' 은(는) 올바르지 않아 기본값(${formatDefault(setting)})을 씁니다." +
                        (setting.type.rangeText?.let { " 허용: $it" } ?: "")
            } else {
                values[setting.path] = parsed
            }
        }

        if (!source.contains(ShrinkPhase.PATH)) {
            source.set(ShrinkPhase.PATH, ShrinkPhase.DEFAULTS.map { it.toFile() })
            source.setComments(ShrinkPhase.PATH, listOf(ShrinkPhase.COMMENT))
            added += ShrinkPhase.PATH
            shrinkPhases = ShrinkPhase.DEFAULTS
        } else {
            val parsed = ShrinkPhase.parse(source.get(ShrinkPhase.PATH), warnings)
            shrinkPhases = parsed ?: ShrinkPhase.DEFAULTS.also {
                warnings += "${ShrinkPhase.PATH}: 목록 형식이 아니어서 기본 단계를 씁니다."
            }
        }

        return LoadReport(warnings, added)
    }

    /**
     * 명령어 입력으로 값을 바꾸고 파일에도 쓴다. 저장(save)은 호출하는 쪽이 한다.
     *
     * @return 성공하면 바뀐 값의 표기
     */
    fun setFromInput(source: ConfigSource, path: String, raw: String): Result<String> {
        val setting = Keys.byPath(path) ?: return Result.failure(IllegalArgumentException("없는 설정입니다: $path"))
        return applyInput(source, setting, raw)
    }

    private fun <T : Any> applyInput(source: ConfigSource, setting: Setting<T>, raw: String): Result<String> =
        setting.type.parse(raw).map { value ->
            values[setting.path] = value
            source.set(setting.path, setting.type.toFile(value))
            listeners.forEach { it(setting) }
            setting.type.format(value)
        }

    /** 현재 값 표기. 목록 출력과 탭 완성에 쓴다. */
    fun <T : Any> formatted(setting: Setting<T>): String = setting.type.format(get(setting))

    fun <T : Any> suggestions(setting: Setting<T>): List<String> = setting.type.suggestions(get(setting))

    private fun <T : Any> readValue(source: ConfigSource, setting: Setting<T>): T? =
        setting.type.fromFile(source.get(setting.path))

    private fun <T : Any> writeDefault(source: ConfigSource, setting: Setting<T>) {
        source.set(setting.path, setting.type.toFile(setting.default))
        source.setComments(setting.path, listOf(setting.description))
    }

    private fun <T : Any> formatDefault(setting: Setting<T>): String = setting.type.format(setting.default)
}