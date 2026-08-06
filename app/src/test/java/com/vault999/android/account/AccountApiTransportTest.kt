package com.vault999.android.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountApiTransportTest {
    @Test
    fun `maps the Discord avatar field returned by the account service`() {
        val payload = Json.parseToJsonElement(
            """{"id":"discord_42","display_name":"Listener","discord_username":"listener","discord_avatar":"https://cdn.discordapp.com/avatars/42/avatar.png"}""",
        ).jsonObject

        val account = accountFromJson(payload)

        assertEquals("Listener", account.displayName)
        assertEquals("listener", account.discordUsername)
        assertEquals("https://cdn.discordapp.com/avatars/42/avatar.png", account.avatarUrl)
    }

    @Test
    fun `keeps compatibility with the earlier avatar alias`() {
        val payload = Json.parseToJsonElement(
            """{"id":"discord_42","display_name":"Listener","avatar_url":"https://cdn.discordapp.com/avatars/42/legacy.png"}""",
        ).jsonObject

        assertEquals("https://cdn.discordapp.com/avatars/42/legacy.png", accountFromJson(payload).avatarUrl)
    }
}
