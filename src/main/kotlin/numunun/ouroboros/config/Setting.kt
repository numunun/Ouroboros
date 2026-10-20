package numunun.ouroboros.config

/**
 * 설정 항목 하나.
 *
 * @param path        config.yml 경로이자 /ouro set 에 쓰는 키
 * @param description 파일 주석과 명령어 도움말에 같이 쓴다
 */
class Setting<T : Any>(
    val path: String,
    val type: SettingType<T>,
    val default: T,
    val description: String,
) {
    init {
        require(type.fromFile(type.toFile(default)) == default) { "기본값이 자기 타입 검사를 통과하지 못한다: $path" }
    }

    override fun toString(): String = path
}