package com.xmoyi.nainaisv.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun migration1To2BackfillsSeriesColumns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS dramas (
                            id TEXT NOT NULL PRIMARY KEY,
                            bvid TEXT NOT NULL,
                            cid INTEGER NOT NULL,
                            page INTEGER NOT NULL,
                            title TEXT NOT NULL,
                            ownerMid INTEGER NOT NULL,
                            ownerName TEXT NOT NULL,
                            coverUrl TEXT NOT NULL,
                            durationMs INTEGER NOT NULL,
                            width INTEGER NOT NULL,
                            height INTEGER NOT NULL,
                            publishedAt INTEGER NOT NULL,
                            playable INTEGER NOT NULL,
                            candidate INTEGER NOT NULL,
                            score INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val db = helper.writableDatabase
            db.execSQL(
                """
                INSERT INTO dramas (id, bvid, cid, page, title, ownerMid, ownerName, coverUrl,
                    durationMs, width, height, publishedAt, playable, candidate, score, updatedAt)
                VALUES ('BV1old:5', 'BV1old', 5, 1, '旧标题', 1, '作者', '', 100, 0, 0, 1, 1, 0, 0, 1)
                """.trimIndent(),
            )

            AppDatabase.MIGRATION_1_2.migrate(db)

            db.query("SELECT seriesKey, seriesTitle FROM dramas WHERE id = 'BV1old:5'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("bv:BV1old", cursor.getString(0))
                assertEquals("旧标题", cursor.getString(1))
            }
        }
    }
}
