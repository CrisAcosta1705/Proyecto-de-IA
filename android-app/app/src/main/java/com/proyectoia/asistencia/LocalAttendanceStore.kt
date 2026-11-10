package com.proyectoia.asistencia

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

const val SEX_MALE = "MASCULINO"
const val SEX_FEMALE = "FEMENINO"
const val SEX_UNSPECIFIED = "NO_ESPECIFICADO"

enum class StudentIdentityConflict {
    CARNET,
    NAME
}

private val identityMarksRegex = Regex("\\p{M}+")
private val identityWhitespaceRegex = Regex("\\s+")

internal fun canonicalStudentIdentity(value: String): String {
    return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(identityMarksRegex, "")
        .lowercase(Locale.ROOT)
        .replace(identityWhitespaceRegex, " ")
}

data class LocalAttendanceStatus(
    val sessionId: Long?,
    val attendanceCount: Int
)

data class LocalStudentProfile(
    val studentId: String,
    val name: String,
    val sex: String,
    val embeddingCount: Int,
    val photoJpeg: ByteArray?
)

data class LocalStudentServerUpload(
    val studentId: String,
    val name: String,
    val sex: String,
    val referenceCount: Int
)

data class LegacyFaceReference(
    val embeddingId: Long,
    val studentId: String,
    val captureId: String,
    val photoJpeg: ByteArray
)

data class LocalFaceReference(
    val referenceId: Long,
    val studentId: String,
    val photoJpeg: ByteArray,
    val mirroredPhotoJpeg: ByteArray?,
    val createdAt: Long
)

data class LocalReferenceCapture(
    val originalEmbedding: FloatArray,
    val mirroredEmbedding: FloatArray,
    val originalPhotoJpeg: ByteArray,
    val mirroredPhotoJpeg: ByteArray,
    val captureId: String = UUID.randomUUID().toString(),
    val sourceCamera: String = "UNKNOWN",
    val pose: String = "UNSPECIFIED",
    val quality: Float = 1f
)

data class LocalAttendanceSummary(
    val sessionId: Long,
    val course: String,
    val attendanceDate: String,
    val total: Int,
    val male: Int,
    val female: Int,
    val unspecified: Int
)

data class LocalAttendanceRecord(
    val studentId: String,
    val studentName: String,
    val sex: String,
    val similarity: Float,
    val recordedAt: Long
)

data class LocalAttendanceReport(
    val course: String,
    val attendanceDate: String,
    val records: List<LocalAttendanceRecord>
) {
    val maleCount: Int
        get() = records.count { it.sex == SEX_MALE }
    val femaleCount: Int
        get() = records.count { it.sex == SEX_FEMALE }
    val unspecifiedCount: Int
        get() = records.count { it.sex == SEX_UNSPECIFIED }
}

data class LocalRecognitionFace(
    val bounds: Rect,
    val studentId: String?,
    val studentName: String?,
    val similarity: Float,
    val attendanceRecorded: Boolean
) {
    val recognized: Boolean
        get() = studentId != null
}

data class LocalRecognitionBatch(
    val faces: List<LocalRecognitionFace>,
    val attendanceCount: Int,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val recognizedCount: Int
        get() = faces.count { it.recognized }
}

class LocalAttendanceStore(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val dataCipher = LocalDataCipher("asistencia_biometric_data_v1")

    companion object {
        private const val DATABASE_NAME = "asistencia_local.db"
        private const val DATABASE_VERSION = 6
        private const val BIOMETRIC_CIPHER_VERSION = "1"
        private const val MAX_EMBEDDINGS_PER_STUDENT = 5
        private const val THUMBNAIL_MAX_DIMENSION = 256
        private const val REFERENCE_MAX_DIMENSION = 1600
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
            cursor.moveToFirst()
        }
        db.rawQuery("PRAGMA temp_store = MEMORY", null).use { cursor ->
            cursor.moveToFirst()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE students (
                student_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                sex TEXT NOT NULL DEFAULT '$SEX_UNSPECIFIED',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE face_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                student_id TEXT NOT NULL,
                embedding BLOB NOT NULL,
                photo_jpeg BLOB NOT NULL,
                model_id TEXT NOT NULL,
                capture_id TEXT NOT NULL,
                variant TEXT NOT NULL DEFAULT 'ORIGINAL',
                active INTEGER NOT NULL DEFAULT 1,
                quality REAL NOT NULL DEFAULT 1.0,
                source_camera TEXT NOT NULL DEFAULT 'UNKNOWN',
                pose TEXT NOT NULL DEFAULT 'UNSPECIFIED',
                quarantine_reason TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(student_id) REFERENCES students(student_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE attendance_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                course TEXT NOT NULL,
                attendance_date TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE attendance (
                session_id INTEGER NOT NULL,
                student_id TEXT NOT NULL,
                similarity REAL NOT NULL,
                recorded_at INTEGER NOT NULL,
                PRIMARY KEY(session_id, student_id),
                FOREIGN KEY(session_id) REFERENCES attendance_sessions(id),
                FOREIGN KEY(student_id) REFERENCES students(student_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_face_embeddings_student ON face_embeddings(student_id)"
        )
        db.execSQL(
            "CREATE INDEX idx_face_embeddings_capture ON face_embeddings(capture_id, active)"
        )
        createServerImportsTable(db)
        createDeletedStudentsTable(db)
        createSecurityMetadataTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                ALTER TABLE face_embeddings
                ADD COLUMN model_id TEXT NOT NULL DEFAULT '${LocalFaceRecognizer.MODEL_ID}'
                """.trimIndent()
            )
            createServerImportsTable(db)
        }
        if (oldVersion < 3) {
            db.execSQL(
                "ALTER TABLE students ADD COLUMN sex TEXT NOT NULL " +
                    "DEFAULT '$SEX_UNSPECIFIED'"
            )
            db.execSQL(
                "ALTER TABLE attendance_sessions " +
                    "ADD COLUMN attendance_date TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                """
                UPDATE attendance_sessions
                SET attendance_date = date(started_at / 1000, 'unixepoch', 'localtime')
                WHERE attendance_date = ''
                """.trimIndent()
            )
        }
        if (oldVersion < 4) {
            createDeletedStudentsTable(db)
        }
        if (oldVersion < 5) {
            createSecurityMetadataTable(db)
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN capture_id TEXT")
            db.execSQL(
                "ALTER TABLE face_embeddings ADD COLUMN variant TEXT NOT NULL " +
                    "DEFAULT 'ORIGINAL'"
            )
            db.execSQL(
                "ALTER TABLE face_embeddings ADD COLUMN active INTEGER NOT NULL DEFAULT 1"
            )
            db.execSQL(
                "ALTER TABLE face_embeddings ADD COLUMN quality REAL NOT NULL DEFAULT 1.0"
            )
            db.execSQL(
                "ALTER TABLE face_embeddings ADD COLUMN source_camera TEXT NOT NULL " +
                    "DEFAULT 'UNKNOWN'"
            )
            db.execSQL(
                "ALTER TABLE face_embeddings ADD COLUMN pose TEXT NOT NULL DEFAULT 'UNSPECIFIED'"
            )
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN quarantine_reason TEXT")
            db.execSQL(
                "UPDATE face_embeddings SET capture_id = 'legacy-' || id " +
                    "WHERE capture_id IS NULL OR capture_id = ''"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_face_embeddings_capture " +
                    "ON face_embeddings(capture_id, active)"
            )
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) migrateSensitiveData(db)
    }

    fun registerStudent(
        studentId: String,
        name: String,
        sex: String,
        embedding: FloatArray,
        photoJpeg: ByteArray,
        existingStudentId: String? = null
    ) {
        val cleanId = studentId.trim()
        val cleanName = name.trim()
        require(cleanId.isNotBlank() && cleanName.isNotBlank()) {
            "El carnet y el nombre son obligatorios."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            requireNoIdentityConflict(db, cleanId, cleanName, existingStudentId)
            db.delete("deleted_students", "student_id = ?", arrayOf(cleanId))
            saveStudentEmbedding(db, cleanId, cleanName, sex, embedding, photoJpeg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun registerStudentSamples(
        studentId: String,
        name: String,
        sex: String,
        samples: List<LocalReferenceCapture>,
        existingStudentId: String? = null
    ) {
        val cleanId = studentId.trim()
        val cleanName = name.trim()
        require(cleanId.isNotBlank() && cleanName.isNotBlank()) {
            "El carnet y el nombre son obligatorios."
        }
        require(samples.isNotEmpty()) { "Se necesita al menos una muestra facial." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            requireNoIdentityConflict(db, cleanId, cleanName, existingStudentId)
            db.delete("deleted_students", "student_id = ?", arrayOf(cleanId))
            db.delete("server_imports", "student_id = ?", arrayOf(cleanId))
            db.delete("face_embeddings", "student_id = ?", arrayOf(cleanId))
            samples.forEach { capture ->
                saveStudentCapture(
                    db,
                    cleanId,
                    cleanName,
                    sex,
                    capture
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun legacyFaceReferences(): List<LegacyFaceReference> {
        val db = readableDatabase
        val references = db.rawQuery(
            """
            SELECT id, student_id, capture_id
            FROM face_embeddings
            WHERE model_id != ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(LocalFaceRecognizer.MODEL_ID)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
                    )
                }
            }
        }
        return references.mapNotNull { (embeddingId, studentId, captureId) ->
            readFacePhotoBlob(db, embeddingId)?.let { photo ->
                LegacyFaceReference(embeddingId, studentId, captureId, photo)
            }
        }
    }

    fun referencesMissingMirror(): List<LegacyFaceReference> {
        val db = readableDatabase
        val rows = db.rawQuery(
            """
            SELECT original.id, original.student_id, original.capture_id
            FROM face_embeddings AS original
            WHERE original.model_id = ?
              AND original.variant = 'ORIGINAL'
              AND original.active = 1
              AND NOT EXISTS(
                  SELECT 1 FROM face_embeddings AS mirrored
                  WHERE mirrored.student_id = original.student_id
                    AND mirrored.capture_id = original.capture_id
                    AND mirrored.variant = 'MIRRORED'
              )
            ORDER BY original.id
            """.trimIndent(),
            arrayOf(LocalFaceRecognizer.MODEL_ID)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        return rows.mapNotNull { (embeddingId, studentId, captureId) ->
            readFacePhotoBlob(db, embeddingId)?.let { photo ->
                LegacyFaceReference(embeddingId, studentId, captureId, photo)
            }
        }
    }

    fun addMirroredFaceReference(
        reference: LegacyFaceReference,
        embedding: FloatArray,
        mirroredPhotoJpeg: ByteArray
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val profile = db.rawQuery(
                "SELECT name, sex FROM students WHERE student_id = ? LIMIT 1",
                arrayOf(reference.studentId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
            } ?: return false
            saveStudentEmbedding(
                db,
                reference.studentId,
                profile.first,
                profile.second,
                embedding,
                mirroredPhotoJpeg,
                reference.captureId,
                "MIRRORED"
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    fun replaceLegacyFaceReference(
        reference: LegacyFaceReference,
        embedding: FloatArray
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val updated = db.update(
                "face_embeddings",
                ContentValues().apply {
                    put("embedding", encodeEmbedding(embedding, reference.studentId))
                    put("model_id", LocalFaceRecognizer.MODEL_ID)
                },
                "id = ? AND student_id = ?",
                arrayOf(reference.embeddingId.toString(), reference.studentId)
            ) > 0
            if (updated) {
                db.update(
                    "server_imports",
                    ContentValues().apply {
                        put("model_id", LocalFaceRecognizer.MODEL_ID)
                    },
                    "embedding_id = ?",
                    arrayOf(reference.embeddingId.toString())
                )
                db.setTransactionSuccessful()
            }
            updated
        } finally {
            db.endTransaction()
        }
    }

    fun needsServerImport(studentId: String, photoSha256: String): Boolean {
        val cleanId = studentId.trim()
        return readableDatabase.rawQuery(
            """
            SELECT 1
            FROM server_imports
            WHERE student_id = ? AND photo_sha256 = ? AND model_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(cleanId, photoSha256, LocalFaceRecognizer.MODEL_ID)
        ).use { cursor -> !cursor.moveToFirst() }
    }

    fun isStudentPermanentlyDeleted(studentId: String): Boolean {
        return readableDatabase.rawQuery(
            "SELECT 1 FROM deleted_students WHERE student_id = ? LIMIT 1",
            arrayOf(studentId.trim())
        ).use { cursor -> cursor.moveToFirst() }
    }

    fun allowStudentRegistration(studentId: String) {
        writableDatabase.delete(
            "deleted_students",
            "student_id = ?",
            arrayOf(studentId.trim())
        )
    }

    fun listStudents(): List<LocalStudentProfile> {
        val db = readableDatabase
        val rows = db.rawQuery(
            """
            SELECT s.student_id, s.name, s.sex,
                   COUNT(DISTINCT CASE WHEN e.active = 1 THEN e.capture_id END)
                       AS embedding_count,
                   (
                       SELECT latest.id
                       FROM face_embeddings latest
                       WHERE latest.student_id = s.student_id
                         AND latest.variant = 'ORIGINAL'
                         AND latest.active = 1
                       ORDER BY latest.id DESC
                       LIMIT 1
                   ) AS photo_embedding_id
            FROM students s
            LEFT JOIN face_embeddings e ON e.student_id = s.student_id
            GROUP BY s.student_id, s.name, s.sex
            ORDER BY s.name COLLATE NOCASE, s.student_id
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StudentProfileRow(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getInt(3),
                            if (cursor.isNull(4)) null else cursor.getLong(4)
                        )
                    )
                }
            }
        }
        return rows.map { row ->
            LocalStudentProfile(
                studentId = row.studentId,
                name = row.name,
                sex = row.sex,
                embeddingCount = row.embeddingCount,
                photoJpeg = row.photoEmbeddingId?.let { embeddingId ->
                    readFacePhotoBlob(db, embeddingId)?.let { photo ->
                        compactPhotoJpeg(photo, THUMBNAIL_MAX_DIMENSION, 82)
                    }
                }
            )
        }
    }

    fun listStudentsForServerUpload(): List<LocalStudentServerUpload> {
        return readableDatabase.rawQuery(
            """
            SELECT s.student_id, s.name, s.sex,
                   COUNT(DISTINCT CASE WHEN e.active = 1 THEN e.capture_id END)
                       AS reference_count
            FROM students s
            LEFT JOIN face_embeddings e ON e.student_id = s.student_id
            GROUP BY s.student_id, s.name, s.sex
            ORDER BY s.name COLLATE NOCASE, s.student_id
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalStudentServerUpload(
                            studentId = cursor.getString(0),
                            name = cursor.getString(1),
                            sex = cursor.getString(2),
                            referenceCount = cursor.getInt(3)
                        )
                    )
                }
            }
        }
    }

    fun findStudentIdentityConflict(
        studentId: String,
        name: String,
        existingStudentId: String? = null
    ): StudentIdentityConflict? {
        return findStudentIdentityConflict(
            readableDatabase,
            studentId.trim(),
            name.trim(),
            existingStudentId
        )
    }

    fun facePhotosForServerUpload(studentId: String): List<ByteArray> {
        val db = readableDatabase
        val embeddingIds = db.query(
            "face_embeddings",
            arrayOf("id"),
            "student_id = ? AND variant = 'ORIGINAL' AND active = 1",
            arrayOf(studentId.trim()),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
        return embeddingIds.mapNotNull { embeddingId ->
            readFacePhotoBlob(db, embeddingId)?.let { photo ->
                compactPhotoJpeg(photo, REFERENCE_MAX_DIMENSION, 90)
            }
        }
    }

    fun faceReferences(studentId: String): List<LocalFaceReference> {
        val cleanId = studentId.trim()
        val db = readableDatabase
        val rows = db.query(
            "face_embeddings",
            arrayOf("id", "created_at", "capture_id"),
            "student_id = ? AND variant = 'ORIGINAL' AND active = 1",
            arrayOf(cleanId),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)))
                }
            }
        }
        return rows.mapNotNull { (referenceId, createdAt, captureId) ->
            readFacePhotoBlob(db, referenceId)?.let { photo ->
                val mirroredId = db.rawQuery(
                    "SELECT id FROM face_embeddings WHERE student_id = ? " +
                        "AND capture_id = ? AND variant = 'MIRRORED' LIMIT 1",
                    arrayOf(cleanId, captureId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
                LocalFaceReference(
                    referenceId = referenceId,
                    studentId = cleanId,
                    photoJpeg = compactPhotoJpeg(photo, 640, 86) ?: photo,
                    mirroredPhotoJpeg = mirroredId?.let { id ->
                        readFacePhotoBlob(db, id)?.let { mirrored ->
                            compactPhotoJpeg(mirrored, 640, 86) ?: mirrored
                        }
                    },
                    createdAt = createdAt
                )
            }
        }
    }

    fun replaceFaceReference(
        studentId: String,
        referenceId: Long,
        capture: LocalReferenceCapture
    ): Boolean {
        val cleanId = studentId.trim()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val captureId = db.rawQuery(
                "SELECT capture_id FROM face_embeddings " +
                    "WHERE id = ? AND student_id = ? LIMIT 1",
                arrayOf(referenceId.toString(), cleanId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return false
            val profile = db.rawQuery(
                "SELECT name, sex FROM students WHERE student_id = ? LIMIT 1",
                arrayOf(cleanId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
            } ?: return false
            db.delete(
                "face_embeddings",
                "student_id = ? AND capture_id = ?",
                arrayOf(cleanId, captureId)
            )
            saveStudentCapture(
                db,
                cleanId,
                profile.first,
                profile.second,
                capture.copy(captureId = captureId)
            )
            run {
                db.delete("server_imports", "student_id = ?", arrayOf(cleanId))
                db.setTransactionSuccessful()
            }
            true
        } finally {
            db.endTransaction()
        }
    }

    fun deleteFaceReference(studentId: String, referenceId: Long): Int? {
        val cleanId = studentId.trim()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val captureId = db.rawQuery(
                "SELECT capture_id FROM face_embeddings " +
                    "WHERE id = ? AND student_id = ? LIMIT 1",
                arrayOf(referenceId.toString(), cleanId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: return null
            db.delete(
                "face_embeddings",
                "capture_id = ? AND student_id = ?",
                arrayOf(captureId, cleanId)
            )
            db.delete("server_imports", "student_id = ?", arrayOf(cleanId))
            val remaining = db.rawQuery(
                "SELECT COUNT(DISTINCT capture_id) FROM face_embeddings " +
                    "WHERE student_id = ? AND active = 1",
                arrayOf(cleanId)
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            db.setTransactionSuccessful()
            remaining
        } finally {
            db.endTransaction()
        }
    }

    fun updateStudentProfile(
        studentId: String,
        name: String,
        sex: String
    ): LocalStudentProfile? {
        val cleanId = studentId.trim()
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "El nombre es obligatorio." }
        requireNoIdentityConflict(
            writableDatabase,
            cleanId,
            cleanName,
            existingStudentId = cleanId
        )
        val updated = writableDatabase.update(
            "students",
            ContentValues().apply {
                put("name", cleanName)
                put("sex", normalizeSex(sex))
            },
            "student_id = ?",
            arrayOf(cleanId)
        )
        if (updated == 0) return null
        return listStudents().firstOrNull { it.studentId == cleanId }
    }

    private fun requireNoIdentityConflict(
        db: SQLiteDatabase,
        studentId: String,
        name: String,
        existingStudentId: String?
    ) {
        when (findStudentIdentityConflict(db, studentId, name, existingStudentId)) {
            StudentIdentityConflict.CARNET -> {
                throw IllegalArgumentException("Ya existe un estudiante con ese carnet.")
            }
            StudentIdentityConflict.NAME -> {
                throw IllegalArgumentException("Ya existe un estudiante con ese nombre.")
            }
            null -> Unit
        }
    }

    private fun findStudentIdentityConflict(
        db: SQLiteDatabase,
        studentId: String,
        name: String,
        existingStudentId: String?
    ): StudentIdentityConflict? {
        val canonicalId = canonicalStudentIdentity(studentId)
        val excludedId = existingStudentId
            ?.takeIf { canonicalStudentIdentity(it) == canonicalId }
            ?.let(::canonicalStudentIdentity)
            ?.takeIf { it.isNotBlank() }
        val rows = db.rawQuery(
            "SELECT student_id, name FROM students",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val storedId = cursor.getString(0)
                    if (canonicalStudentIdentity(storedId) == excludedId) continue
                    add(storedId to cursor.getString(1))
                }
            }
        }
        if (rows.any { (storedId, _) ->
                canonicalStudentIdentity(storedId) == canonicalId
            }
        ) {
            return StudentIdentityConflict.CARNET
        }
        val canonicalName = canonicalStudentIdentity(name)
        if (rows.any { (_, storedName) ->
                canonicalStudentIdentity(storedName) == canonicalName
            }
        ) {
            return StudentIdentityConflict.NAME
        }
        return null
    }

    fun updateStudentSex(studentId: String, sex: String): String? {
        val cleanId = studentId.trim()
        val name = readableDatabase.query(
            "students",
            arrayOf("name"),
            "student_id = ?",
            arrayOf(cleanId),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: return null
        return updateStudentProfile(cleanId, name, sex)?.name
    }

    fun importServerStudent(
        studentId: String,
        name: String,
        sex: String,
        embedding: FloatArray,
        photoJpeg: ByteArray,
        photoSha256: String
    ) {
        val cleanId = studentId.trim()
        val cleanName = name.trim()
        require(cleanId.isNotBlank() && cleanName.isNotBlank() && photoSha256.isNotBlank()) {
            "El registro del servidor esta incompleto."
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previousEmbeddingId = db.query(
                "server_imports",
                arrayOf("embedding_id"),
                "student_id = ?",
                arrayOf(cleanId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (previousEmbeddingId != null) {
                db.delete(
                    "face_embeddings",
                    "id = ?",
                    arrayOf(previousEmbeddingId.toString())
                )
            }
            val embeddingId = saveStudentEmbedding(
                db,
                cleanId,
                cleanName,
                sex,
                embedding,
                photoJpeg
            )
            db.insertWithOnConflict(
                "server_imports",
                null,
                ContentValues().apply {
                    put("student_id", cleanId)
                    put("photo_sha256", photoSha256)
                    put("model_id", LocalFaceRecognizer.MODEL_ID)
                    put("embedding_id", embeddingId)
                    put("imported_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearRecognition(studentId: String): String? {
        val cleanId = studentId.trim()
        val name = readableDatabase.query(
            "students",
            arrayOf("name"),
            "student_id = ?",
            arrayOf(cleanId),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null
        writableDatabase.delete("face_embeddings", "student_id = ?", arrayOf(cleanId))
        return name
    }

    fun deleteStudent(studentId: String): String? {
        val cleanId = studentId.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val name = db.query(
                "students",
                arrayOf("name"),
                "student_id = ?",
                arrayOf(cleanId),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            db.delete("attendance", "student_id = ?", arrayOf(cleanId))
            db.delete("students", "student_id = ?", arrayOf(cleanId))
            db.insertWithOnConflict(
                "deleted_students",
                null,
                ContentValues().apply {
                    put("student_id", cleanId)
                    put("deleted_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
            return name
        } finally {
            db.endTransaction()
        }
    }

    fun attendanceStatus(course: String, attendanceDate: String): LocalAttendanceStatus {
        return readableDatabase.rawQuery(
            """
            SELECT s.id, COUNT(a.student_id)
            FROM attendance_sessions s
            LEFT JOIN attendance a ON a.session_id = s.id
            WHERE s.course = ? AND s.attendance_date = ?
            GROUP BY s.id
            ORDER BY s.id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(course.trim(), attendanceDate)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                LocalAttendanceStatus(cursor.getLong(0), cursor.getInt(1))
            } else {
                LocalAttendanceStatus(null, 0)
            }
        }
    }

    fun attendanceSummaries(): List<LocalAttendanceSummary> {
        return readableDatabase.rawQuery(
            """
            WITH latest_sessions AS (
                SELECT MAX(id) AS id
                FROM attendance_sessions
                GROUP BY course, attendance_date
            )
            SELECT sessions.id,
                   sessions.course,
                   sessions.attendance_date,
                   COUNT(attendance.student_id) AS total,
                   SUM(CASE WHEN students.sex = '$SEX_MALE' THEN 1 ELSE 0 END) AS male,
                   SUM(CASE WHEN students.sex = '$SEX_FEMALE' THEN 1 ELSE 0 END) AS female,
                   SUM(CASE WHEN attendance.student_id IS NOT NULL
                            AND students.sex NOT IN ('$SEX_MALE', '$SEX_FEMALE')
                            THEN 1 ELSE 0 END) AS unspecified
            FROM latest_sessions latest
            JOIN attendance_sessions sessions ON sessions.id = latest.id
            LEFT JOIN attendance ON attendance.session_id = sessions.id
            LEFT JOIN students ON students.student_id = attendance.student_id
            GROUP BY sessions.id, sessions.course, sessions.attendance_date
            ORDER BY sessions.attendance_date DESC, sessions.id DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalAttendanceSummary(
                            sessionId = cursor.getLong(0),
                            course = cursor.getString(1),
                            attendanceDate = cursor.getString(2),
                            total = cursor.getInt(3),
                            male = cursor.getInt(4),
                            female = cursor.getInt(5),
                            unspecified = cursor.getInt(6)
                        )
                    )
                }
            }
        }
    }

    fun createSession(
        course: String,
        attendanceDate: String = LocalDate.now().toString()
    ): Long {
        val cleanCourse = course.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val status = attendanceStatus(cleanCourse, attendanceDate)
            val sessionId = status.sessionId
            if (sessionId != null) {
                db.update(
                    "attendance_sessions",
                    ContentValues().apply {
                        putNull("finished_at")
                    },
                    "id = ?",
                    arrayOf(sessionId.toString())
                )
                db.setTransactionSuccessful()
                return sessionId
            }

            val created = db.insertOrThrow(
                "attendance_sessions",
                null,
                ContentValues().apply {
                    put("course", cleanCourse)
                    put("attendance_date", attendanceDate)
                    put("started_at", System.currentTimeMillis())
                }
            )
            db.setTransactionSuccessful()
            return created
        } finally {
            db.endTransaction()
        }
    }

    fun finishSession(sessionId: Long): Boolean {
        return writableDatabase.update(
            "attendance_sessions",
            ContentValues().apply { put("finished_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(sessionId.toString())
        ) > 0
    }

    fun attendanceReport(course: String, attendanceDate: String): LocalAttendanceReport {
        val status = attendanceStatus(course, attendanceDate)
        val sessionId = status.sessionId
        val records = if (sessionId == null) {
            emptyList()
        } else {
            readableDatabase.rawQuery(
                """
                SELECT a.student_id, s.name, s.sex, a.similarity, a.recorded_at
                FROM attendance a
                JOIN students s ON s.student_id = a.student_id
                WHERE a.session_id = ?
                ORDER BY s.sex, s.name, s.student_id
                """.trimIndent(),
                arrayOf(sessionId.toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LocalAttendanceRecord(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getFloat(3),
                                cursor.getLong(4)
                            )
                        )
                    }
                }
            }
        }
        return LocalAttendanceReport(course.trim(), attendanceDate, records)
    }

    fun recognize(
        sessionId: Long,
        samples: List<LocalFaceSample>,
        imageWidth: Int,
        imageHeight: Int
    ): LocalRecognitionBatch {
        val db = writableDatabase
        val registered = loadEmbeddings(db)
        val plan = recognitionPlan(samples, registered)
        val results = mutableListOf<LocalRecognitionFace>()

        db.beginTransaction()
        try {
            for ((index, sample) in samples.withIndex()) {
                val scores = plan.rankedBySample[index]
                val recognizedMatch = plan.acceptedBySample[index]
                val bestSimilarity = scores.firstOrNull()?.similarity ?: 0f

                if (recognizedMatch == null) {
                    results += LocalRecognitionFace(
                        Rect(sample.bounds),
                        null,
                        null,
                        bestSimilarity,
                        false
                    )
                    continue
                }

                val inserted = db.insertWithOnConflict(
                    "attendance",
                    null,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("student_id", recognizedMatch.studentId)
                        put("similarity", recognizedMatch.similarity)
                        put("recorded_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                ) != -1L
                results += LocalRecognitionFace(
                    Rect(sample.bounds),
                    recognizedMatch.studentId,
                    recognizedMatch.studentName,
                    recognizedMatch.similarity,
                    inserted
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return LocalRecognitionBatch(
            results,
            attendanceCount(db, sessionId),
            imageWidth,
            imageHeight
        )
    }

    fun previewRecognition(
        samples: List<LocalFaceSample>,
        imageWidth: Int,
        imageHeight: Int
    ): LocalRecognitionBatch {
        val plan = recognitionPlan(samples, loadEmbeddings(readableDatabase))
        val results = samples.mapIndexed { index, sample ->
            val scores = plan.rankedBySample[index]
            val accepted = plan.acceptedBySample[index]
            LocalRecognitionFace(
                Rect(sample.bounds),
                accepted?.studentId,
                accepted?.studentName,
                accepted?.similarity ?: scores.firstOrNull()?.similarity ?: 0f,
                false
            )
        }
        return LocalRecognitionBatch(results, 0, imageWidth, imageHeight)
    }

    fun verifyRegistrationCandidate(
        studentId: String,
        studentName: String,
        referenceEmbeddings: List<FloatArray>,
        samples: List<LocalFaceSample>,
        imageWidth: Int,
        imageHeight: Int
    ): LocalRecognitionBatch {
        require(referenceEmbeddings.isNotEmpty()) {
            "Se necesita al menos una referencia para verificar el registro."
        }
        val registered = loadEmbeddings(readableDatabase)
            .filterNot { it.studentId == studentId } +
            referenceEmbeddings.map { embedding ->
                RecognitionReference(studentId, studentName, embedding)
            }
        val plan = UnifiedEmbeddingMatcher(registered).plan(samples.map { it.embedding })
        val results = samples.mapIndexed { index, sample ->
            val scores = plan.rankedBySample[index]
            val accepted = plan.acceptedBySample[index]
            LocalRecognitionFace(
                Rect(sample.bounds),
                accepted?.studentId,
                accepted?.studentName,
                accepted?.similarity ?: scores.firstOrNull()?.similarity ?: 0f,
                false
            )
        }
        return LocalRecognitionBatch(results, 0, imageWidth, imageHeight)
    }

    private fun loadEmbeddings(db: SQLiteDatabase): List<RecognitionReference> {
        return db.rawQuery(
            """
            SELECT e.student_id, s.name, e.embedding, e.capture_id, e.variant
            FROM face_embeddings e
            JOIN students s ON s.student_id = e.student_id
            WHERE e.model_id = ? AND e.active = 1
            """.trimIndent(),
            arrayOf(LocalFaceRecognizer.MODEL_ID)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RecognitionReference(
                            cursor.getString(0),
                            cursor.getString(1),
                            decodeEmbedding(cursor.getBlob(2), cursor.getString(0)),
                            cursor.getString(3),
                            cursor.getString(4)
                        )
                    )
                }
            }
        }
    }

    private fun attendanceCount(db: SQLiteDatabase, sessionId: Long): Int {
        return db.rawQuery(
            "SELECT COUNT(*) FROM attendance WHERE session_id = ?",
            arrayOf(sessionId.toString())
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun pruneEmbeddings(db: SQLiteDatabase, studentId: String) {
        val staleCaptureIds = db.rawQuery(
            """
            SELECT capture_id, MAX(id) AS latest_id
            FROM face_embeddings
            WHERE student_id = ?
            GROUP BY capture_id
            ORDER BY latest_id DESC
            """.trimIndent(),
            arrayOf(studentId)
        ).use { cursor ->
            buildList {
                var position = 0
                while (cursor.moveToNext()) {
                    if (position >= MAX_EMBEDDINGS_PER_STUDENT) add(cursor.getString(0))
                    position++
                }
            }
        }
        staleCaptureIds.forEach { captureId ->
            db.delete(
                "face_embeddings",
                "student_id = ? AND capture_id = ?",
                arrayOf(studentId, captureId)
            )
        }
    }

    private fun saveStudentCapture(
        db: SQLiteDatabase,
        studentId: String,
        name: String,
        sex: String,
        capture: LocalReferenceCapture
    ): Long {
        val originalId = saveStudentEmbedding(
            db,
            studentId,
            name,
            sex,
            capture.originalEmbedding,
            capture.originalPhotoJpeg,
            capture.captureId,
            "ORIGINAL",
            capture.sourceCamera,
            capture.pose,
            capture.quality
        )
        saveStudentEmbedding(
            db,
            studentId,
            name,
            sex,
            capture.mirroredEmbedding,
            capture.mirroredPhotoJpeg,
            capture.captureId,
            "MIRRORED",
            capture.sourceCamera,
            capture.pose,
            capture.quality
        )
        return originalId
    }

    private fun saveStudentEmbedding(
        db: SQLiteDatabase,
        studentId: String,
        name: String,
        sex: String,
        embedding: FloatArray,
        photoJpeg: ByteArray,
        captureId: String = UUID.randomUUID().toString(),
        variant: String = "ORIGINAL",
        sourceCamera: String = "UNKNOWN",
        pose: String = "UNSPECIFIED",
        quality: Float = 1f
    ): Long {
        val storedPhoto = compactPhotoJpeg(photoJpeg, REFERENCE_MAX_DIMENSION, 90) ?: photoJpeg
        val student = ContentValues().apply {
            put("name", name)
            put("sex", normalizeSex(sex))
        }
        val updated = db.update("students", student, "student_id = ?", arrayOf(studentId))
        if (updated == 0) {
            student.put("student_id", studentId)
            student.put("created_at", System.currentTimeMillis())
            db.insertOrThrow("students", null, student)
        }

        val embeddingId = db.insertOrThrow(
            "face_embeddings",
            null,
            ContentValues().apply {
                put("student_id", studentId)
                put("embedding", encodeEmbedding(embedding, studentId))
                put("photo_jpeg", encryptPhoto(storedPhoto, studentId))
                put("model_id", LocalFaceRecognizer.MODEL_ID)
                put("capture_id", captureId)
                put("variant", variant)
                put("active", 1)
                put("quality", quality.coerceIn(0f, 1f))
                put("source_camera", sourceCamera.uppercase(Locale.ROOT))
                put("pose", pose.uppercase(Locale.ROOT))
                put("created_at", System.currentTimeMillis())
            }
        )
        pruneEmbeddings(db, studentId)
        return embeddingId
    }

    private fun readFacePhotoBlob(db: SQLiteDatabase, embeddingId: Long): ByteArray? {
        val studentId = db.rawQuery(
            "SELECT student_id FROM face_embeddings WHERE id = ? LIMIT 1",
            arrayOf(embeddingId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null
        val stored = readRawFacePhotoBlob(db, embeddingId) ?: return null
        return dataCipher.decrypt(stored, photoPurpose(studentId))
    }

    private fun readRawFacePhotoBlob(db: SQLiteDatabase, embeddingId: Long): ByteArray? {
        val statement = db.compileStatement(
            "SELECT photo_jpeg FROM face_embeddings WHERE id = ? LIMIT 1"
        )
        return try {
            statement.bindLong(1, embeddingId)
            val descriptor = statement.simpleQueryForBlobFileDescriptor()
            if (descriptor == null) {
                null
            } else {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        } finally {
            statement.close()
        }
    }

    private fun compactPhotoJpeg(
        photoJpeg: ByteArray,
        maxDimension: Int,
        quality: Int
    ): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photoJpeg, 0, photoJpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            photoJpeg,
            0,
            photoJpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return@runCatching null

        val rotation = runCatching {
            ExifInterface(ByteArrayInputStream(photoJpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val oriented = if (degrees == 0f) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(degrees) },
                true
            ).also { decoded.recycle() }
        }

        val largestDimension = maxOf(oriented.width, oriented.height)
        val scaled = if (largestDimension <= maxDimension) {
            oriented
        } else {
            val scale = maxDimension.toFloat() / largestDimension
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * scale).toInt().coerceAtLeast(1),
                (oriented.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { oriented.recycle() }
        }

        ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                scaled.recycle()
                return@runCatching null
            }
            scaled.recycle()
            output.toByteArray()
        }
    }.getOrNull()

    private fun normalizeSex(sex: String): String {
        return when (sex.trim().uppercase()) {
            SEX_MALE -> SEX_MALE
            SEX_FEMALE -> SEX_FEMALE
            else -> SEX_UNSPECIFIED
        }
    }

    private fun embeddingPurpose(studentId: String): String =
        "face-embedding:v1:$studentId"

    private fun photoPurpose(studentId: String): String =
        "student-photo:v1:$studentId"

    private fun encryptPhoto(photo: ByteArray, studentId: String): ByteArray =
        dataCipher.encrypt(photo, photoPurpose(studentId))

    private fun createSecurityMetadataTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS security_metadata (
                name TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun migrateSensitiveData(db: SQLiteDatabase) {
        createSecurityMetadataTable(db)
        val currentVersion = db.rawQuery(
            "SELECT value FROM security_metadata WHERE name = 'biometric_cipher_version'",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (currentVersion == BIOMETRIC_CIPHER_VERSION) return

        val rows = db.rawQuery(
            "SELECT id, student_id, embedding FROM face_embeddings ORDER BY id",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getBlob(2)))
                }
            }
        }
        db.beginTransaction()
        try {
            rows.forEach { (embeddingId, studentId, storedEmbedding) ->
                val values = ContentValues()
                if (!dataCipher.isEncrypted(storedEmbedding)) {
                    values.put(
                        "embedding",
                        dataCipher.encrypt(storedEmbedding, embeddingPurpose(studentId))
                    )
                }
                val storedPhoto = readRawFacePhotoBlob(db, embeddingId)
                if (storedPhoto != null && !dataCipher.isEncrypted(storedPhoto)) {
                    values.put(
                        "photo_jpeg",
                        dataCipher.encrypt(storedPhoto, photoPurpose(studentId))
                    )
                }
                if (values.size() > 0) {
                    db.update(
                        "face_embeddings",
                        values,
                        "id = ?",
                        arrayOf(embeddingId.toString())
                    )
                }
            }
            db.insertWithOnConflict(
                "security_metadata",
                null,
                ContentValues().apply {
                    put("name", "biometric_cipher_version")
                    put("value", BIOMETRIC_CIPHER_VERSION)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createServerImportsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS server_imports (
                student_id TEXT PRIMARY KEY,
                photo_sha256 TEXT NOT NULL,
                model_id TEXT NOT NULL,
                embedding_id INTEGER NOT NULL,
                imported_at INTEGER NOT NULL,
                FOREIGN KEY(student_id) REFERENCES students(student_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createDeletedStudentsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS deleted_students (
                student_id TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun encodeEmbedding(embedding: FloatArray, studentId: String): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach(buffer::putFloat)
        return dataCipher.encrypt(buffer.array(), embeddingPurpose(studentId))
    }

    private fun decodeEmbedding(blob: ByteArray, studentId: String): FloatArray {
        val plaintext = dataCipher.decrypt(blob, embeddingPurpose(studentId))
        require(plaintext.size % Float.SIZE_BYTES == 0) {
            "El embedding protegido tiene una longitud invalida."
        }
        val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(plaintext.size / Float.SIZE_BYTES) { buffer.float }
    }

    private fun recognitionPlan(
        samples: List<LocalFaceSample>,
        registered: List<RecognitionReference>
    ): UnifiedRecognitionPlan {
        return UnifiedEmbeddingMatcher(registered).plan(samples.map { it.embedding })
    }

    private data class StudentProfileRow(
        val studentId: String,
        val name: String,
        val sex: String,
        val embeddingCount: Int,
        val photoEmbeddingId: Long?
    )

}
