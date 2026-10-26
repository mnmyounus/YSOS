package com.mnmyounus.ysos

import com.mnmyounus.ysos.data.EmergencyContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyContactTest {

    @Test
    fun testEmergencyContactCreation() {
        val contact = EmergencyContact(
            id = 1L,
            countryCode = "US",
            countryName = "United States",
            category = "police",
            name = "Police / Fire / Ambulance",
            phoneNumber = "911",
            email = "fema@dhs.gov",
            notes = "Primary 911"
        )

        assertEquals(1L, contact.id)
        assertEquals("US", contact.countryCode)
        assertEquals("United States", contact.countryName)
        assertEquals("police", contact.category)
        assertEquals("911", contact.phoneNumber)
        assertEquals("fema@dhs.gov", contact.email)
        assertEquals("Primary 911", contact.notes)
    }

    @Test
    fun testEmergencyContactDefaults() {
        val contact = EmergencyContact(
            countryCode = "IN",
            countryName = "India",
            category = "ambulance",
            name = "National Ambulance",
            phoneNumber = "108"
        )

        assertEquals(0L, contact.id)
        assertEquals("", contact.email)
        assertEquals("", contact.notes)
    }
}
