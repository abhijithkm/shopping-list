package space.abhijithkm.shoppinglist.data.collab

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.abhijithkm.shoppinglist.data.remote.AppHubException

class CollabTypesTest {
    @Test
    fun `join code alphabet leaves out the ambiguous characters and has 32 symbols`() {
        assertEquals(32, JoinCode.ALPHABET.length)
        assertEquals(32, JoinCode.ALPHABET.toSet().size)
        for (ch in "0O1I") assertFalse("$ch must not be in the alphabet", ch in JoinCode.ALPHABET)
        assertTrue(JoinCode.ALPHABET.all { it.isDigit() || it.isUpperCase() })
    }

    @Test
    fun `a generated code is eight valid characters and codes do not repeat`() {
        val random = SecureRandom()
        val codes = List(500) { JoinCode.generate(random) }
        codes.forEach { assertEquals(JoinCode.LENGTH, it.length) }
        codes.forEach { assertTrue(JoinCode.isValid(it)) }
        // 2^40 possibilities: a repeat among 500 draws would mean the generator is broken.
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `every symbol of the alphabet shows up when many codes are drawn`() {
        val seen = HashSet<Char>()
        val random = SecureRandom()
        repeat(400) { JoinCode.generate(random).forEach(seen::add) }
        assertEquals(JoinCode.ALPHABET.toSet(), seen)
    }

    @Test
    fun `typed codes are normalised before they are checked`() {
        assertEquals("ABCD2345", JoinCode.normalize(" abcd-2345 "))
        assertEquals("ABCD2345", JoinCode.normalize("ab cd 23 45"))
        assertTrue(JoinCode.isValid(JoinCode.normalize("abcd-2345")))
    }

    @Test
    fun `malformed codes are not valid`() {
        assertFalse(JoinCode.isValid(""))
        assertFalse(JoinCode.isValid("ABCD234"))
        assertFalse(JoinCode.isValid("ABCD23456"))
        assertFalse(JoinCode.isValid("ABCD234O")) // letter O
        assertFalse(JoinCode.isValid("ABCD2341")) // digit 1
        assertFalse(JoinCode.isValid("abcd2345")) // not normalised
    }

    @Test
    fun `a code is shown with a dash that is never part of it`() {
        assertEquals("ABCD-2345", JoinCode.display("ABCD2345"))
        assertEquals("SHORT", JoinCode.display("SHORT"))
    }

    @Test
    fun `an issued code never prints itself`() {
        val issued = IssuedCode("ABCD2345", 1L)
        assertFalse(issued.toString().contains("ABCD2345"))
    }

    @Test
    fun `server tokens are found in the error message and mapped`() {
        assertEquals("sl:expired", CollabTokens.parse("sl:expired"))
        assertEquals("sl:too_many_attempts", CollabTokens.parse("Request failed: sl:too_many_attempts."))
        assertNull(CollabTokens.parse("Duplicate entry"))
        assertNull(CollabTokens.parse(null))
        assertEquals(CollabFailure.Expired, CollabTokens.failureOf("sl:expired"))
        assertEquals(CollabFailure.NotAMember, CollabTokens.failureOf("sl:not_a_member"))
        assertEquals(CollabFailure.Conflict, CollabTokens.failureOf(CollabTokens.CONFLICT))
        assertEquals(CollabFailure.Unknown, CollabTokens.failureOf("sl:something_new"))
        assertEquals(CollabFailure.Unknown, CollabTokens.failureOf(null))
    }

    @Test
    fun `every token of the contract has its own failure`() {
        val tokens = listOf(
            "sl:forbidden", "sl:not_a_member", "sl:not_found", "sl:not_pending", "sl:expired", "sl:invalid_code",
            "sl:already_member", "sl:already_pending", "sl:limit_reached", "sl:too_many_attempts", "sl:conflict", "sl:server_not_ready",
        )
        val failures = tokens.map(CollabTokens::failureOf)
        assertFalse(failures.contains(CollabFailure.Unknown))
        assertEquals(tokens.size, failures.toSet().size)
    }

    @Test
    fun `http answers map to failures`() {
        fun http(status: Int, message: String) = AppHubException.Http(status, message)
        assertEquals(CollabFailure.NotAvailable, http(404, "Not found").toCollabFailure())
        assertEquals(CollabFailure.NotAvailable, http(403, "Forbidden").toCollabFailure())
        assertEquals(CollabFailure.SignInNeeded, http(401, "Unauthorized").toCollabFailure())
        assertEquals(CollabFailure.RateLimited, http(429, "Too many").toCollabFailure())
        assertEquals(CollabFailure.NotPending, http(422, "sl:not_pending").toCollabFailure())
        assertEquals(CollabFailure.Unknown, http(422, "Unprocessable").toCollabFailure())
        assertEquals(CollabFailure.Unknown, http(500, "sl:expired").toCollabFailure())
        assertEquals(CollabFailure.Offline, AppHubException.Network(java.io.IOException("x")).toCollabFailure())
    }

    @Test
    fun `only a 422 with the conflict token is a revision conflict`() {
        assertTrue(AppHubException.Http(422, "sl:conflict").isRevisionConflict)
        assertFalse(AppHubException.Http(422, "sl:not_a_member").isRevisionConflict)
        assertFalse(AppHubException.Http(409, "sl:conflict").isRevisionConflict)
        assertFalse(AppHubException.Http(500, "Internal").isRevisionConflict)
    }
}
