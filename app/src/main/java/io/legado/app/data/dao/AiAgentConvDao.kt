package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiAgentConv
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAgentConvDao {

    @get:Query("select * from aiAgentConvs order by updateTime desc")
    val all: List<AiAgentConv>

    @Query("select * from aiAgentConvs order by updateTime desc")
    fun flowAll(): Flow<List<AiAgentConv>>

    @Query("select * from aiAgentConvs where kind = :kind order by updateTime desc")
    fun flowByKind(kind: String): Flow<List<AiAgentConv>>

    @Query("select * from aiAgentConvs where convKey = :key limit 1")
    fun getByKey(key: String): AiAgentConv?

    @Query("select * from aiAgentConvs where id = :id")
    fun get(id: Long): AiAgentConv?

    @Query("select * from aiAgentConvs where refBreakdownId = :bdId and kind = 'breakdown' order by updateTime desc limit 50")
    fun getByBreakdown(bdId: Long): List<AiAgentConv>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg convs: AiAgentConv): List<Long>

    @Update
    fun update(conv: AiAgentConv)

    @Query("update aiAgentConvs set state = :state, updateTime = :time where id = :id")
    fun setState(id: Long, state: Int, time: Long)

    @Query("update aiAgentConvs set rollingSummary = :summary, summarizedThroughMessageId = :throughId, updateTime = :time where id = :id")
    fun updateRollingSummary(id: Long, summary: String, throughId: Long, time: Long)

    @Query("update aiAgentConvs set lastError = :err, state = 0, updateTime = :time where id = :id")
    fun setError(id: Long, err: String, time: Long)

    @Query("delete from aiAgentConvs where id = :id")
    fun delete(id: Long)

    @Query("delete from aiAgentConvs where refBreakdownId = :bdId and kind = 'breakdown'")
    fun purgeByBreakdown(bdId: Long)
}
