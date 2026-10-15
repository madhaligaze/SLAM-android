package kz.slam.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BrigadeDao {
    /** Порядок как у сервера (по его id), новые несинхронизированные — в конце. */
    @Query("SELECT * FROM brigades WHERE deleted = 0 ORDER BY serverId IS NULL, serverId, createdAt")
    fun observeAll(): Flow<List<BrigadeEntity>>

    @Query("SELECT * FROM brigades WHERE id = :id")
    fun observe(id: String): Flow<BrigadeEntity?>

    @Query("SELECT * FROM brigades WHERE id = :id")
    suspend fun get(id: String): BrigadeEntity?

    @Query("SELECT * FROM brigades")
    suspend fun all(): List<BrigadeEntity>

    @Upsert
    suspend fun upsert(b: BrigadeEntity)

    @Query("DELETE FROM brigades WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE brigades SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: String, serverId: Int)

    /** Чисто — только если за время выгрузки бригаду не правили снова. */
    @Query("UPDATE brigades SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    /** Данные с сервера — только поверх неизменённой здесь бригады. */
    @Query("""UPDATE brigades SET name = :name, company = :company, task = :task, objectKey = :objectKey,
              workers = :workers, updatedAt = :now
              WHERE id = :id AND dirty = 0 AND updatedAt = :seenUpdatedAt""")
    suspend fun applyRemote(id: String, seenUpdatedAt: Long, name: String, company: String, task: String,
                            objectKey: String, workers: List<String>, now: Long): Int

    @Query("UPDATE brigades SET serverJob = :summary WHERE id = :id")
    suspend fun setServerJob(id: String, summary: ServerJobSummary?)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE brigadeId = :brigadeId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(brigadeId: String): Flow<JobEntity?>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE status IN ('queued', 'running') ORDER BY createdAt")
    suspend fun active(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE reported = 0 AND status IN ('done', 'error') AND serverBlockId IS NOT NULL")
    suspend fun unreported(): List<JobEntity>

    @Upsert
    suspend fun upsert(j: JobEntity)

    @Upsert
    suspend fun upsertFields(f: JobFieldsEntity)

    @Query("SELECT * FROM job_fields WHERE jobId = :jobId AND idx = :idx")
    suspend fun fields(jobId: String, idx: Int): JobFieldsEntity?

    @Query("SELECT * FROM job_fields WHERE jobId = :jobId ORDER BY idx")
    suspend fun allFields(jobId: String): List<JobFieldsEntity>

    /** История: последние 10 отправок каждой бригады. */
    @Query("""DELETE FROM jobs WHERE status NOT IN ('queued', 'running') AND id NOT IN (
              SELECT j.id FROM jobs j WHERE j.brigadeId = jobs.brigadeId ORDER BY j.createdAt DESC LIMIT 10)""")
    suspend fun trim()

    @Query("DELETE FROM job_fields WHERE jobId NOT IN (SELECT id FROM jobs)")
    suspend fun trimFields()
}

@Database(entities = [BrigadeEntity::class, JobEntity::class, JobFieldsEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun brigades(): BrigadeDao
    abstract fun jobs(): JobDao

    companion object {
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "slam.db").build()
    }
}
