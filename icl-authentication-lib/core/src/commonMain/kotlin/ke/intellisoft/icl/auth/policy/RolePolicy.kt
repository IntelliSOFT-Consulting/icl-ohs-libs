package ke.intellisoft.icl.auth.policy

/** Pure function, no framework dependency - identical to @RequiresRole's runtime check. */
object RolePolicy {
    fun isAllowed(required: List<String>, actual: Set<String>): Boolean =
        required.any { it in actual }
}
