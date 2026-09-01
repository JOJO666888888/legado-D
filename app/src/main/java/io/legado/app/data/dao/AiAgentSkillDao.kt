package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.AiAgentSkill
import kotlinx.coroutines.flow.Flow

@Dao
interface AiAgentSkillDao {

    @get:Query("select * from aiAgentSkills order by readOnly desc, enabled desc, updateTime desc")
    val all: List<AiAgentSkill>

    @Query("select * from aiAgentSkills order by readOnly desc, enabled desc, updateTime desc")
    fun flowAll(): Flow<List<AiAgentSkill>>

    @Query("select * from aiAgentSkills where category = :category order by readOnly desc, enabled desc, updateTime desc")
    fun flowByCategory(category: String): Flow<List<AiAgentSkill>>

    @Query("select * from aiAgentSkills where category = :category and enabled = 1 order by readOnly desc, updateTime desc")
    fun flowEnabledByCategory(category: String): Flow<List<AiAgentSkill>>

    @Query("select * from aiAgentSkills where id = :id")
    fun get(id: Long): AiAgentSkill?

    @Query("select * from aiAgentSkills where builtinId = :builtinId")
    fun getByBuiltinId(builtinId: String): AiAgentSkill?

    @Query("select * from aiAgentSkills where migratedFromTemplateId = :templateId")
    fun getByMigratedTemplateId(templateId: Long): AiAgentSkill?

    @Query("select * from aiAgentSkills where name = :name and category = :category")
    fun getByNameAndCategory(name: String, category: String): AiAgentSkill?

    /** 默认拆书 skill:先取内置 BUILTIN_BREAKDOWN_QINGSHAN, 不存在回退任意 breakdown 分类第一个 enabled */
    @Query("select * from aiAgentSkills where category = 'breakdown' and enabled = 1 order by (builtinId = 'breakdown_qingshan') desc, readOnly desc, updateTime desc limit 1")
    fun getDefaultBreakdownSkill(): AiAgentSkill?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg skills: AiAgentSkill): List<Long>

    @Update
    fun update(skill: AiAgentSkill)

    @Delete
    fun delete(skill: AiAgentSkill)
}
