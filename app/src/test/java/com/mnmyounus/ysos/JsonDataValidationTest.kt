package com.mnmyounus.ysos

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

class JsonDataValidationTest {

    data class ContactRecord(
        val countryCode: String,
        val countryName: String,
        val category: String,
        val name: String,
        val phoneNumber: String,
        val email: String,
        val notes: String
    )

    @Test
    fun testEmergencyJsonFileValidity() {
        val jsonFile = File("src/main/assets/emergency_data.json")
        val altFile = File("app/src/main/assets/emergency_data.json")
        val fileToRead = if (jsonFile.exists()) jsonFile else altFile

        assertTrue("emergency_data.json should exist", fileToRead.exists())

        val reader = InputStreamReader(FileInputStream(fileToRead))
        val type = object : TypeToken<List<ContactRecord>>() {}.type
        val contacts: List<ContactRecord> = Gson().fromJson(reader, type)
        reader.close()

        assertNotNull("Contacts list should not be null", contacts)
        assertTrue("Should contain at least 50 emergency contacts", contacts.size >= 50)

        val validCategories = setOf("police", "ambulance", "fire", "disaster")

        for (c in contacts) {
            assertFalse("Country code must not be empty", c.countryCode.isBlank())
            assertFalse("Country name must not be empty", c.countryName.isBlank())
            assertFalse("Contact name must not be empty", c.name.isBlank())
            assertFalse("Phone number must not be empty", c.phoneNumber.isBlank())
            assertTrue("Category '${c.category}' should be valid", validCategories.contains(c.category))
        }
    }
}
