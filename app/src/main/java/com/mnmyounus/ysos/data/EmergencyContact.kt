package com.mnmyounus.ysos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "emergency_contacts")
data class EmergencyContact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val countryCode: String,       // ISO 3166-1 alpha-2, e.g. "US"
    val countryName: String,
    val category: String,          // "police", "ambulance", "fire", "disaster"
    val name: String,
    val phoneNumber: String,
    val email: String = "",
    val notes: String = ""
)
