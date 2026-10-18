package com.rohanc.navgate.data.local

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator

@RunWith(AndroidJUnit4::class)
class RegionDocumentImporterTest {
    private val store=object:RegionStore{
        override suspend fun active(regionId:String):RegionPack?=null
        override suspend fun version(regionId:String,version:String):RegionPack?=null
        override suspend fun activate(pack:RegionPack,sha256:String){error("Invalid documents must not activate data")}
    }
    @Test fun disabledUpdatesNeverOpenDocuments()=runBlocking {
        var opens=0
        val importer=RegionDocumentImporter(SignedPackImport(RegionPackInstaller(store)),{opens++;error("Must not open")})
        assertTrue(importer.import("test",Uri.parse("content://test/pack"),Uri.parse("content://test/signature")) is InstallResult.Rejected)
        assertEquals(0,opens)
    }
    @Test fun missingSignatureClosesPackAndRejectsFilePaths()=runBlocking {
        val key=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair().public
        var closed=false;var opens=0
        val payload=object:ByteArrayInputStream(byteArrayOf()) { override fun close(){closed=true;super.close()} }
        val importer=RegionDocumentImporter(SignedPackImport(RegionPackInstaller(store,key)),{uri -> opens++;if(uri.lastPathSegment=="pack") payload else null})
        assertTrue(importer.import("test",Uri.parse("file:///pack"),Uri.parse("content://test/signature")) is InstallResult.Rejected)
        assertEquals(0,opens)
        assertTrue(importer.import("test",Uri.parse("content://test/pack"),Uri.parse("content://test/signature")) is InstallResult.Rejected)
        assertTrue(closed);assertEquals(2,opens)
    }
}
