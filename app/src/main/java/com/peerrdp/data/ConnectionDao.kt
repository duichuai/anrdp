package com.peerrdp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY lastUsedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun getById(id: Long): ConnectionEntity?

    @Insert
    suspend fun insert(entity: ConnectionEntity): Long

    @Update
    suspend fun update(entity: ConnectionEntity)

    @Delete
    suspend fun delete(entity: ConnectionEntity)

    @Query("UPDATE connections SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)
}
