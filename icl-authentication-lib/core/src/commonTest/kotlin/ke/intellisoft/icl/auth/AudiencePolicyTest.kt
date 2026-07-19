package ke.intellisoft.icl.auth

import ke.intellisoft.icl.auth.policy.AudiencePolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression test for the audience-check bug: a token with no `aud` claim must be rejected. */
class AudiencePolicyTest {

    @Test
    fun `rejects a null audience`() {
        assertFalse(AudiencePolicy.isValid(null, "icl-backend"))
    }

    @Test
    fun `rejects an audience that does not contain the client id`() {
        assertFalse(AudiencePolicy.isValid(listOf("some-other-client"), "icl-backend"))
    }

    @Test
    fun `accepts an audience containing the client id`() {
        assertTrue(AudiencePolicy.isValid(listOf("icl-backend"), "icl-backend"))
        assertTrue(AudiencePolicy.isValid(listOf("some-other-client", "icl-backend"), "icl-backend"))
    }
}
