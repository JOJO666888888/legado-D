package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiAgentMsg
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAgentMsgDao {

    @get:Query("select * from aiAgentMsgs order by id asc")
    val all: List<AiAgentMsg>

    @Query("select * from aiAgentMsgs where convId = :convId order by sortOrder asc")
    fun flowByConv(convId: Long): Flow<List<AiAgentMsg>>

    @Query("select * from aiAgentMsgs where convId = :convId order by sortOrder asc")
    fun getByConv(convId: Long): List<AiAgentMsg>

    @Query("select * from aiAgentMsgs where id = :id")
    fun get(id: Long): AiAgentMsg?

    @Query("select COALESCE(max(sortOrder), -1) + 1 from aiAgentMsgs where convId = :convId")
    fun nextSortOrder(convId: Long): Int

    @Query("select count(*) from aiAgentMsgs where convId = :convId and status in (1, 3)")
    fun countPending(convId: Long): Int

    /** 流式阶段:ASSISTANT 最新一条消息内容追加(节流 5~10 chunks) */
    @Query("update aiAgentMsgs set content = content || :delta, updateTime = :time where id = :id")
    fun appendContent(id: Long, delta: String, time: Long)

    @Query("update aiAgentMsgs set content = :content, status = :status, updateTime = :time, written = :written where id = :id")
    fun finalizeContent(id: Long, content: String, status: Int, written: Boolean, time: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg msgs: AiAgentMsg): List<Long>

    @Update
    fun update(msg: AiAgentMsg)

    @Query("delete from aiAgentMsgs where id = :id")
    fun delete(id: Long)

    @Query("delete from aiAgentMsgs where convId = :convId")
    fun deleteByConv(convId: Long)
}
