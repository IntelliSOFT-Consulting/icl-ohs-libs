package ke.intellisoft.icl.auth

/** A replayed (already-rotated-away) refresh token was presented - the token family has been revoked. */
class TokenReuseDetectedException : RuntimeException("This refresh token has already been used. The session has been revoked.")

/** The refresh token's family/session has already been revoked (e.g. prior logout). */
class SessionRevokedException : RuntimeException("This session has been revoked.")

/** The presented token failed signature/issuer verification. */
class InvalidTokenException : RuntimeException("Invalid or expired token.")

/** The caller's token lacks a role required for this operation. */
class InsufficientRoleException(requiredRoles: List<String>) :
    RuntimeException("Requires one of: ${requiredRoles.joinToString(", ")}")
