package com.rohanc.navgate.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data object Verifying : ImportState
    data class Finished(val result: InstallResult) : ImportState
    data object Cancelled : ImportState
}

/** Manual document import: bounded streams, trusted signatures, explicit region and atomic installation. */
class SignedPackImport(private val installer: RegionPackInstaller,private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock=Mutex()
    private val mutable=MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = mutable.asStateFlow()
    val enabled: Boolean get() = installer.trustedUpdatesEnabled

    /** Owns both streams, including cancellation before IO starts and concurrent-import rejection. */
    suspend fun import(pack:InputStream,signature:InputStream,regionId:String):InstallResult {
        try {
            if (!lock.tryLock()) return InstallResult.Rejected(listOf("Another pack import is already running"))
            try {
                val result=withContext(io) {
                    if(!enabled) return@withContext InstallResult.Rejected(listOf("Signed updates are unavailable until a trusted public key is bundled"))
                    mutable.value=ImportState.Reading
                    val bytes=readBounded(pack,RegionPackInstaller.MAX_BYTES)
                    val sig=readBounded(signature,1024)
                    mutable.value=ImportState.Verifying
                    installer.installSigned(bytes,sig,regionId)
                }
                mutable.value=ImportState.Finished(result)
                return result
            } catch(cancelled:CancellationException) { mutable.value=ImportState.Cancelled;throw cancelled }
            catch(_:Exception) {
                val result=InstallResult.Rejected(listOf("Unable to read a bounded signed pack; existing data was retained"))
                mutable.value=ImportState.Finished(result)
                return result
            } finally { lock.unlock() }
        } finally {
            // A read-stream close failure cannot turn a successfully committed install into a false rejection.
            runCatching { pack.close() }; runCatching { signature.close() }
        }
    }

    private suspend fun readBounded(input:InputStream,maxBytes:Int):ByteArray {
        val output=ByteArrayOutputStream()
        val chunk=ByteArray(8192)
        while(true) {
            coroutineContext.ensureActive()
            val count=input.read(chunk,0,minOf(chunk.size,maxBytes-output.size()+1))
            if(count<0) return output.toByteArray()
            if(count==0) throw IllegalArgumentException("Input stream did not advance")
            require(output.size()+count<=maxBytes) { "Input exceeds import limit" }
            output.write(chunk,0,count)
        }
    }
}
