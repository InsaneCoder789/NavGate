package com.rohanc.navgate.core

import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature

class SignedPackImportTest {
    private class Tracked(bytes:ByteArray):ByteArrayInputStream(bytes) {
        var closed=false
        override fun close() { closed=true;super.close() }
    }
    @Test fun `disabled import closes documents and never installs unsigned data`() = runTest {
        val store=MemoryRegionStore()
        val importer=SignedPackImport(RegionPackInstaller(store),StandardTestDispatcher(testScheduler))
        val pack=Tracked(Json.encodeToString(fixturePack()).toByteArray());val sig=Tracked(byteArrayOf())
        assertFalse(importer.enabled)
        assertTrue(importer.import(pack,sig,"test") is InstallResult.Rejected)
        assertTrue(pack.closed && sig.closed);assertNull(store.active("test"))
    }
    @Test fun `signed stream import enforces selected region signature and bounded input`() = runTest {
        val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        val store=MemoryRegionStore()
        val importer=SignedPackImport(RegionPackInstaller(store,pair.public),StandardTestDispatcher(testScheduler))
        val bytes=Json.encodeToString(fixturePack()).toByteArray()
        val signature=Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(bytes);sign()}
        assertTrue(importer.import(Tracked(bytes),Tracked(signature),"other") is InstallResult.Rejected)
        assertNull(store.active("test"))
        assertTrue(importer.import(Tracked(bytes),Tracked(signature),"test") is InstallResult.Installed)
        assertTrue(importer.import(Tracked(bytes+32.toByte()),Tracked(signature),"test") is InstallResult.Rejected)
        val huge=Tracked(ByteArray(RegionPackInstaller.MAX_BYTES+1));val sig=Tracked(signature)
        assertTrue(importer.import(huge,sig,"test") is InstallResult.Rejected)
        assertTrue(huge.closed && sig.closed)
        assertEquals("v1",store.active("test")?.version)
    }
    @Test fun `activation failure reports rejection without replacing the old graph`() = runTest {
        val memory=MemoryRegionStore();memory.activate(fixturePack(),"original")
        val failing=object:RegionStore by memory {
            override suspend fun activate(pack:RegionPack,sha256:String) { error("transaction rolled back") }
        }
        assertTrue(RegionPackInstaller(failing).installBundled(Json.encodeToString(fixturePack().copy(version="v2")).toByteArray()) is InstallResult.Rejected)
        assertEquals("v1",memory.active("test")?.version)
    }
}
