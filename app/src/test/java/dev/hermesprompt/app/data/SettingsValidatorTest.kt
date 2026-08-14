package dev.hermesprompt.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SettingsValidator] — URL normalization and validation.
 *
 * These tests run on the JVM (no Android dependencies).
 */
class SettingsValidatorTest {

    // ── Valid inputs ──────────────────────────────────────────────────────────

    @Test
    fun `https URL is returned unchanged`() {
        val result = SettingsValidator.normalize("https://hermes.example.com")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `http URL is accepted for LAN use case`() {
        val result = SettingsValidator.normalize("http://192.168.1.10:8080")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("http://192.168.1.10:8080", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `bare hostname gets https scheme prepended`() {
        val result = SettingsValidator.normalize("hermes.example.com")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `trailing slash is stripped`() {
        val result = SettingsValidator.normalize("https://hermes.example.com/")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `path component is stripped`() {
        val result = SettingsValidator.normalize("https://hermes.example.com/v1/something")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        val result = SettingsValidator.normalize("  https://hermes.example.com  ")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `query string is stripped`() {
        val result = SettingsValidator.normalize("https://hermes.example.com?foo=bar")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("https://hermes.example.com", (result as SettingsValidator.UrlResult.Valid).url)
    }

    @Test
    fun `IP address with port is accepted`() {
        val result = SettingsValidator.normalize("http://10.0.0.1:7777")
        assertTrue(result is SettingsValidator.UrlResult.Valid)
        assertEquals("http://10.0.0.1:7777", (result as SettingsValidator.UrlResult.Valid).url)
    }

    // ── Invalid inputs ────────────────────────────────────────────────────────

    @Test
    fun `empty string returns Invalid`() {
        val result = SettingsValidator.normalize("")
        assertTrue(result is SettingsValidator.UrlResult.Invalid)
    }

    @Test
    fun `blank string returns Invalid`() {
        val result = SettingsValidator.normalize("   ")
        assertTrue(result is SettingsValidator.UrlResult.Invalid)
    }

    // ── isValid helper ────────────────────────────────────────────────────────

    @Test
    fun `isValid returns true for well-formed https URL`() {
        assertTrue(SettingsValidator.isValid("https://hermes.example.com"))
    }

    @Test
    fun `isValid returns false for empty string`() {
        assertFalse(SettingsValidator.isValid(""))
    }
}
