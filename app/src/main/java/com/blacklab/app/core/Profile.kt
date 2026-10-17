package com.blacklab.app.core

enum class Profile(val label: String, val folder: String) {
    PERSONAL("Personal", "Personal"),
    PROFESSIONAL("Professional", "Professional");

    val other: Profile get() = if (this == PERSONAL) PROFESSIONAL else PERSONAL

    companion object {
        fun from(value: String?): Profile = entries.firstOrNull { it.name == value } ?: PERSONAL
    }
}
