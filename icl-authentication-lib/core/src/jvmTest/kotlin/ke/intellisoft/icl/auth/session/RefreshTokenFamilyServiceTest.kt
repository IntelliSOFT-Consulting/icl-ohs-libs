package ke.intellisoft.icl.auth.session

import ke.intellisoft.icl.auth.TestDatabase
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshTokenFamilyServiceTest {

    private val service = RefreshTokenFamilyService()

    @BeforeTest
    fun setUp() {
        TestDatabase.ensureReady()
        TestDatabase.clearAll()
    }

    @Test
    fun `unknown family is reported as first seen`() {
        assertEquals(RotationResult.FirstSeen, service.checkRotation(UUID.randomUUID(), UUID.randomUUID()))
    }

    @Test
    fun `valid rotation is accepted and advances the current token`() {
        val familyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val firstJti = UUID.randomUUID()
        service.createFamily(familyId, userId, firstJti, Instant.now().plusSeconds(3600))
        service.createSession(UUID.randomUUID(), userId, familyId, "curl/8.0", "127.0.0.1")

        assertEquals(RotationResult.Valid, service.checkRotation(familyId, firstJti))

        val secondJti = UUID.randomUUID()
        service.completeRotation(familyId, secondJti, Instant.now().plusSeconds(3600))

        assertEquals(RotationResult.Valid, service.checkRotation(familyId, secondJti))
    }

    @Test
    fun `replaying an already-rotated token is detected as reuse and revokes the family`() {
        val familyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val firstJti = UUID.randomUUID()
        service.createFamily(familyId, userId, firstJti, Instant.now().plusSeconds(3600))
        service.createSession(UUID.randomUUID(), userId, familyId, null, null)

        val secondJti = UUID.randomUUID()
        service.completeRotation(familyId, secondJti, Instant.now().plusSeconds(3600))

        // firstJti has already been rotated away - replaying it is reuse.
        assertEquals(RotationResult.ReuseDetected, service.checkRotation(familyId, firstJti))

        // the family is now revoked, so even the current token is rejected.
        assertEquals(RotationResult.AlreadyRevoked, service.checkRotation(familyId, secondJti))
    }

    @Test
    fun `listSessions scopes strictly to the given user`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val familyA = UUID.randomUUID()
        val familyB = UUID.randomUUID()
        service.createFamily(familyA, userA, UUID.randomUUID(), Instant.now().plusSeconds(3600))
        service.createSession(UUID.randomUUID(), userA, familyA, null, null)
        service.createFamily(familyB, userB, UUID.randomUUID(), Instant.now().plusSeconds(3600))
        service.createSession(UUID.randomUUID(), userB, familyB, null, null)

        val sessions = service.listSessions(userA)
        assertEquals(1, sessions.size)
        assertEquals(familyA, sessions.single().familyId)
    }
}
