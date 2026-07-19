package ke.intellisoft.icl.auth.policy

/** Pure function, no framework dependency - a token with no `aud` claim must be rejected. */
object AudiencePolicy {
    fun isValid(audience: List<String>?, clientId: String): Boolean =
        audience != null && clientId in audience
}
