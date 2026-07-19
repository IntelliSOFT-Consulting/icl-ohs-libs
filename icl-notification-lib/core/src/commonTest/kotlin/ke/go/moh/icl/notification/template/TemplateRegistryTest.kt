package ke.go.moh.icl.notification.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateRegistryTest {
    @Test
    fun `substitutes known placeholders`() {
        val registry = TemplateRegistry()
        registry.register("welcome", "Hello {{name}}, welcome to {{facility}}.")

        val rendered = registry.render("welcome", mapOf("name" to "Asha", "facility" to "Kenyatta NH"))

        assertEquals("Hello Asha, welcome to Kenyatta NH.", rendered)
    }

    @Test
    fun `leaves unknown placeholders untouched`() {
        val registry = TemplateRegistry()
        registry.register("partial", "Hi {{name}}, code {{otp}}.")

        val rendered = registry.render("partial", mapOf("name" to "Asha"))

        assertEquals("Hi Asha, code {{otp}}.", rendered)
    }

    @Test
    fun `throws for unregistered template`() {
        val registry = TemplateRegistry()

        assertFailsWith<TemplateNotFoundException> {
            registry.render("missing", emptyMap())
        }
    }

    @Test
    fun `names returns registered templates sorted`() {
        val registry = TemplateRegistry()
        registry.register("zeta", "z")
        registry.register("alpha", "a")

        assertTrue(registry.names() == listOf("alpha", "zeta"))
    }
}
