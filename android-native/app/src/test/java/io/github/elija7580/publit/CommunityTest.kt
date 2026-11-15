package io.github.elija7580.publit

import io.github.elija7580.publit.data.Support
import io.github.elija7580.publit.data.WhatsNew
import io.github.elija7580.publit.data.Wish
import io.github.elija7580.publit.data.WishesClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wish list, support products and the What's new note (same rules as iOS). */
class CommunityTest {
    @Test fun supportIdsMatchIos() {
        assertEquals("io.github.elija7580.publit.support.monthly.150", Support.monthlyId(150))
        assertEquals(listOf(150, 300, 500, 1000, 2000, 5000), Support.monthlyCents)
        assertEquals(Support.Kind.ONE_TIME to 500, Support.parse(Support.oneTimeId(500)))
        assertNull(Support.parse("io.github.elija7580.publit.support.monthly.123"))
        val ids = listOf(Support.monthlyId(5000), Support.oneTimeId(300), Support.monthlyId(150))
        assertEquals(listOf(Support.monthlyId(150), Support.monthlyId(5000)), Support.sorted(ids, { it }, Support.Kind.MONTHLY))
    }

    @Test fun whatsNewOnlyNewerReleases() {
        assertTrue(WhatsNew.items(WhatsNew.latest).isEmpty())
        assertTrue(WhatsNew.items(WhatsNew.latest - 1).isNotEmpty())
        assertTrue(WhatsNew.entries.all { it.items.size <= 3 })
    }

    @Test fun parsesWishes() {
        val list = WishesClient.parse("""{"wishes":[{"id":2,"title":"Show the optimal exit","body":"OSM","status":"planned","created":1794592760,"votes":2,"mine":true},
            {"id":1,"title":"Wear OS app","status":"something-new","votes":1}]}""")
        assertEquals(listOf(2, 1), list.map { it.id })
        assertEquals(Wish.Status.PLANNED, list[0].status)
        assertEquals(Wish.Status.OPEN, list[1].status)
        assertTrue(list[0].mine)
        assertEquals(WishesClient.Failure.EXISTS, WishesClient.failure(409, ""))
        assertEquals(WishesClient.Failure.LINKS, WishesClient.failure(422, """{"error":"links"}"""))
        assertTrue(WishesClient.isValidVoter(WishesClient.newVoter()))
    }
}
