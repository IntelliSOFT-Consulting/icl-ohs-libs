package ke.go.moh.icl.notification.template

private val PLACEHOLDER_REGEX = Regex("""\{\{\s*([a-zA-Z0-9_.]+)\s*}}""")

class TemplateNotFoundException(name: String) : Exception("No template registered named '$name'")

/**
 * `{{placeholder}}`-substitution template engine, chosen over Thymeleaf so this stays
 * usable from commonMain (Thymeleaf is JVM-only).
 */
class TemplateRegistry {
    private val templates = mutableMapOf<String, String>()

    fun register(name: String, body: String) {
        templates[name] = body
    }

    fun names(): List<String> = templates.keys.sorted()

    fun render(name: String, data: Map<String, String>): String {
        val body = templates[name] ?: throw TemplateNotFoundException(name)
        return PLACEHOLDER_REGEX.replace(body) { match ->
            val key = match.groupValues[1]
            data[key] ?: match.value
        }
    }
}
