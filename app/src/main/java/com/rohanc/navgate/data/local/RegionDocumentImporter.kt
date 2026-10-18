package com.rohanc.navgate.data.local

import android.content.ContentResolver
import android.net.Uri
import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import java.io.InputStream

/** Stitch screens may pass two Storage Access Framework URIs; no file paths or signing keys are accepted. */
class RegionDocumentImporter(
    private val imports:SignedPackImport,
    private val openDocument:(Uri)->InputStream?,
    private val io:CoroutineDispatcher=Dispatchers.IO,
) {
    constructor(imports:SignedPackImport,resolver:ContentResolver):this(imports,{resolver.openInputStream(it)})
    val enabled:Boolean get()=imports.enabled
    val state get()=imports.state
    suspend fun import(regionId:String,pack:Uri,signature:Uri):InstallResult {
        if(!enabled) return InstallResult.Rejected(listOf("No trusted update key is bundled"))
        if(pack.scheme!="content" || signature.scheme!="content") return InstallResult.Rejected(listOf("Choose pack and signature through the document picker"))
        return try { withContext(io) {
            val payload=openDocument(pack) ?: return@withContext InstallResult.Rejected(listOf("Pack document is unavailable"))
            val signed=try { openDocument(signature) }
            catch(error:Exception){runCatching{payload.close()};throw error}
            if(signed==null){runCatching{payload.close()};return@withContext InstallResult.Rejected(listOf("Signature document is unavailable"))}
            imports.import(payload,signed,regionId)
        }} catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){InstallResult.Rejected(listOf("Document access failed; installed region was retained"))}
    }
}
