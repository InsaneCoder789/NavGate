package com.rohanc.navgate.core

/** Circular true-north heading filter. Invalid/low-quality samples invalidate previous alignment. */
class HeadingFilter(private val alpha:Double=0.25,private val maxAgeMillis:Long=2_000) {
    init { require(alpha.isFinite() && alpha>0 && alpha<=1 && maxAgeMillis>0) }
    private var heading:Double?=null
    private var timestamp:Long?=null
    fun reset() { heading=null;timestamp=null }
    fun sample(magneticDegrees:Double,declinationDegrees:Double,elapsedMillis:Long,reliable:Boolean):Double? {
        if(!reliable || !magneticDegrees.isFinite() || !declinationDegrees.isFinite() || elapsedMillis<0) { reset();return null }
        if(timestamp?.let { elapsedMillis<=it }==true) return heading
        val raw=normalize(magneticDegrees+declinationDegrees)
        val previous=if(timestamp?.let { elapsedMillis-it<=maxAgeMillis }==true) heading else null
        heading=previous?.let { normalize(it+delta(raw-it)*alpha) } ?: raw
        timestamp=elapsedMillis
        return heading
    }
    fun current(nowElapsedMillis:Long):Double? = timestamp?.let { if(nowElapsedMillis>=it && nowElapsedMillis-it<=maxAgeMillis) heading else null }
    private fun normalize(value:Double)=((value%360)+360)%360
    private fun delta(value:Double)=((value+540)%360)-180
}
