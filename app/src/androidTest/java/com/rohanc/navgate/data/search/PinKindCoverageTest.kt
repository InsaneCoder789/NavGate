package com.rohanc.navgate.data.search

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Every category and subtype in the shipped place indexes gets an icon kind; very little may fall back to the plain pin. */
@RunWith(AndroidJUnit4::class)
class PinKindCoverageTest {
    private fun coverage(asset: String): Triple<Int, Int, List<String>> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "$asset-coverage.sqlite")
        context.assets.open("search/$asset.sqlite").use { input -> file.outputStream().use { input.copyTo(it) } }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        var total = 0; var other = 0; val worst = ArrayList<Pair<String, Int>>()
        db.rawQuery("SELECT category, subtype, COUNT(*) FROM pois WHERE category NOT IN ('locality') GROUP BY 1, 2", null).use { rows ->
            while (rows.moveToNext()) {
                val c = rows.getString(0).orEmpty(); val s = rows.getString(1).orEmpty(); val n = rows.getInt(2)
                total += n
                if (PinKind.of(c, s) == PinKind.Other) { other += n; worst += "$c/$s" to n }
            }
        }
        db.close(); file.delete()
        return Triple(total, other, worst.sortedByDescending { it.second }.take(12).map { "${it.first}=${it.second}" })
    }

    @Test fun mumbaiAndOdishaAreAlmostEntirelyCovered() {
        for (asset in listOf("mumbai_pois", "odisha_pois")) {
            val (total, other, worst) = coverage(asset)
            android.util.Log.i("KINDQ", "$asset: $other of $total on the plain pin; most common: $worst")
            assertTrue("$asset: $other of $total fall back to the plain pin: $worst", other * 100.0 / total < 2.0)
        }
    }
}
