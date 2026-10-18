package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class PackInstallerTest {
    @Test fun `invalid update leaves previous version intact and missing coordinates remain nullable`() = runTest {
        val store=MemoryRegionStore(); val installer=RegionPackInstaller(store)
        val pack=fixturePack().copy(places=fixturePack().places.map { it.copy(point=null) })
        assertTrue(installer.installBundled(Json.encodeToString(pack).toByteArray()) is InstallResult.Installed)
        val bad=pack.copy(version="v2",edges=pack.edges.map{it.copy(from="unknown")})
        assertTrue(installer.installBundled(Json.encodeToString(bad).toByteArray()) is InstallResult.Rejected)
        assertEquals("v1",store.active("test")?.version)
        assertNull(store.active("test")?.places?.first()?.point)
    }

    @Test fun `signed updates verify trusted key and reject tampering`() = runTest {
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val store=MemoryRegionStore(); val installer=RegionPackInstaller(store,pair.public)
        val bytes=Json.encodeToString(fixturePack()).toByteArray()
        val signature=Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(bytes); sign() }
        assertTrue(installer.installSigned(bytes,signature) is InstallResult.Installed)
        val tampered=bytes.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        assertTrue(installer.installSigned(tampered,signature) is InstallResult.Rejected)
        assertTrue(RegionPackInstaller(store).installSigned(bytes,signature) is InstallResult.Rejected)
        assertEquals("v1",store.active("test")?.version)
    }

    @Test fun `unknown schemas degenerate coverage and reversed geometry are rejected`() {
        val pack=fixturePack()
        assertFalse(PackValidator.validate(pack.copy(schemaVersion=2)).valid)
        assertFalse(PackValidator.validate(pack.copy(coverage=Coverage(listOf(GeoPoint(0.0,0.0),GeoPoint(0.0,1.0),GeoPoint(0.0,2.0))))).valid)
        assertFalse(PackValidator.validate(pack.copy(edges=pack.edges.map{it.copy(geometry=it.geometry.reversed())})).valid)
    }

    @Test fun `empty oversized and malformed JSON are rejected`() = runTest {
        val installer=RegionPackInstaller(MemoryRegionStore())
        assertTrue(installer.installBundled(byteArrayOf()) is InstallResult.Rejected)
        assertTrue(installer.installBundled(ByteArray(RegionPackInstaller.MAX_BYTES+1)) is InstallResult.Rejected)
        assertTrue(installer.installBundled("{broken}".toByteArray()) is InstallResult.Rejected)
    }
}
