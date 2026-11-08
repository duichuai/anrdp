package com.anrdp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TailscaleConfigDao {
    @Query("SELECT * FROM tailscale_configs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TailscaleConfigEntity>>

    @Query("SELECT * FROM tailscale_configs ORDER BY createdAt DESC")
    suspend fun getAll(): List<TailscaleConfigEntity>

    @Query("SELECT * FROM tailscale_configs WHERE id = :id")
    suspend fun getById(id: Long): TailscaleConfigEntity?

    @Insert
    suspend fun insert(config: TailscaleConfigEntity): Long

    @Update
    suspend fun update(config: TailscaleConfigEntity)

    @Query("DELETE FROM tailscale_configs WHERE id = :id")
    suspend fun delete(id: Long)
}
