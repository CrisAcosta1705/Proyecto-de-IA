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
    val photoJpeg: ByteArray
)

data class LocalFaceReference(
    val referenceId: Long,
    val studentId: String,
    val photoJpeg: ByteArray,
    val createdAt: Long
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
        private const val DATABASE_VERSION = 5
        private const val BIOMETRIC_CIPHER_VERSION = "1"
        private const val SIMILARITY_THRESHOLD = 0.50f
        private const val SINGLE_REFERENCE_THRESHOLD = 0.58f
        private const val MINIMUM_SCORE_MARGIN = 0.05f
        private const val MINIMUM_REFERENCE_SUPPORT = 0.38f
        private const val SECOND_REFERENCE_WEIGHT = 0.30f
        private const val MAX_EMBEDDINGS_PER_STUDENT = 5
        private const val THUMBNAIL_MAX_DIMENSION = 256
        private const val REFERENCE_MAX_DIMENSION = 1600
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA secure_delete = ON")
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
        samples: List<Pair<FloatArray, ByteArray>>,
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
            samples.forEach { (embedding, photoJpeg) ->
                saveStudentEmbedding(
                    db,
                    cleanId,
                    cleanName,
                    sex,
                    embedding,
                    photoJpeg
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
            SELECT id, student_id
            FROM face_embeddings
            WHERE model_id != ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(LocalFaceRecognizer.MODEL_ID)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        cursor.getLong(0) to cursor.getString(1)
                    )
                }
            }
        }
        return references.mapNotNull { (embeddingId, studentId) ->
            readFacePhotoBlob(db, embeddingId)?.let { photo ->
                LegacyFaceReference(embeddingId, studentId, photo)
            }
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
            SELECT s.student_id, s.name, s.sex, COUNT(e.id) AS embedding_count,
                   (
                       SELECT latest.id
                       FROM face_embeddings latest
                       WHERE latest.student_id = s.student_id
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
            SELECT s.student_id, s.name, s.sex, COUNT(e.id) AS reference_count
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
            "student_id = ?",
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
            arrayOf("id", "created_at"),
            "student_id = ?",
            arrayOf(cleanId),
            null,
            null,
            "id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to cursor.getLong(1))
                }
            }
        }
        return rows.mapNotNull { (referenceId, createdAt) ->
            readFacePhotoBlob(db, referenceId)?.let { photo ->
                LocalFaceReference(
                    referenceId = referenceId,
                    studentId = cleanId,
                    photoJpeg = compactPhotoJpeg(photo, 640, 86) ?: photo,
                    createdAt = createdAt
                )
            }
        }
    }

    fun replaceFaceReference(
        studentId: String,
        referenceId: Long,
        embedding: FloatArray,
        photoJpeg: ByteArray
    ): Boolean {
        val cleanId = studentId.trim()
        val storedPhoto = compactPhotoJpeg(photoJpeg, REFERENCE_MAX_DIMENSION, 90)
            ?: photoJpeg
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val updated = db.update(
                "face_embeddings",
                ContentValues().apply {
                    put("embedding", encodeEmbedding(embedding, cleanId))
                    put("photo_jpeg", encryptPhoto(storedPhoto, cleanId))
                    put("model_id", LocalFaceRecognizer.MODEL_ID)
                    put("created_at", System.currentTimeMillis())
                },
                "id = ? AND student_id = ?",
                arrayOf(referenceId.toString(), cleanId)
            ) > 0
            if (updated) {
                db.delete("server_imports", "student_id = ?", arrayOf(cleanId))
                db.setTransactionSuccessful()
            }
            updated
        } finally {
            db.endTransaction()
        }
    }

    fun deleteFaceReference(studentId: String, referenceId: Long): Int? {
        val cleanId = studentId.trim()
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val deleted = db.delete(
                "face_embeddings",
                "id = ? AND student_id = ?",
                arrayOf(referenceId.toString(), cleanId)
            )
            if (deleted == 0) return null
            db.delete("server_imports", "student_id = ?", arrayOf(cleanId))
            val remaining = db.rawQuery(
                "SELECT COUNT(*) FROM face_embeddings WHERE student_id = ?",
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
                RegisteredEmbedding(studentId, studentName, embedding)
            }
        val results = samples.map { sample ->
            val scores = rankedScores(sample.embedding, registered)
            val accepted = acceptedScore(scores)
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

    private fun loadEmbeddings(db: SQLiteDatabase): List<RegisteredEmbedding> {
        return db.rawQuery(
            """
            SELECT e.student_id, s.name, e.embedding
            FROM face_embeddings e
            JOIN students s ON s.student_id = e.student_id
            WHERE e.model_id = ?
            """.trimIndent(),
            arrayOf(LocalFaceRecognizer.MODEL_ID)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RegisteredEmbedding(
                            cursor.getString(0),
                            cursor.getString(1),
                            decodeEmbedding(cursor.getBlob(2), cursor.getString(0))
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
        val staleIds = db.query(
            "face_embeddings",
            arrayOf("id"),
            "student_id = ?",
            arrayOf(studentId),
            null,
            null,
            "id DESC"
        ).use { cursor ->
            buildList {
                var position = 0
                while (cursor.moveToNext()) {
                    if (position >= MAX_EMBEDDINGS_PER_STUDENT) add(cursor.getLong(0))
                    position++
                }
            }
        }
        staleIds.forEach { id -> db.delete("face_embeddings", "id = ?", arrayOf(id.toString())) }
    }

    private fun saveStudentEmbedding(
        db: SQLiteDatabase,
        studentId: String,
        name: String,
        sex: String,
        embedding: FloatArray,
        photoJpeg: ByteArray
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

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return -1f
        var similarity = 0f
        for (index in left.indices) similarity += left[index] * right[index]
        return similarity
    }

    private fun rankedScores(
        embedding: FloatArray,
        registered: List<RegisteredEmbedding>
    ): List<StudentScore> {
        return registered
            .asSequence()
            .groupBy { it.studentId }
            .map { (_, embeddings) ->
                val similarities = embeddings
                    .map { cosine(embedding, it.embedding) }
                    .sortedDescending()
                val bestSimilarity = similarities.first()
                val secondSimilarity = similarities.getOrNull(1)
                val combinedSimilarity = if (secondSimilarity == null) {
                    bestSimilarity
                } else {
                    bestSimilarity * (1f - SECOND_REFERENCE_WEIGHT) +
                        secondSimilarity * SECOND_REFERENCE_WEIGHT
                }
                val identity = embeddings.first()
                StudentScore(
                    identity.studentId,
                    identity.studentName,
                    combinedSimilarity,
                    similarities.size,
                    secondSimilarity == null ||
                        secondSimilarity >= MINIMUM_REFERENCE_SUPPORT
                )
            }
            .sortedByDescending { it.similarity }
    }

    private fun acceptedScore(scores: List<StudentScore>): StudentScore? {
        val best = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)
        val hasMargin = second == null ||
            best.similarity - second.similarity >= MINIMUM_SCORE_MARGIN
        val threshold = if (best.referenceCount == 1) {
            SINGLE_REFERENCE_THRESHOLD
        } else {
            SIMILARITY_THRESHOLD
        }
        return best.takeIf {
            it.similarity >= threshold && hasMargin && it.hasReferenceSupport
        }
    }

    private fun recognitionPlan(
        samples: List<LocalFaceSample>,
        registered: List<RegisteredEmbedding>
    ): RecognitionPlan {
        val rankedBySample = samples.map { sample ->
            rankedScores(sample.embedding, registered)
        }
        val candidates = rankedBySample.mapIndexedNotNull { index, scores ->
            acceptedScore(scores)?.let { index to it }
        }.sortedByDescending { (_, score) -> score.similarity }
        val claimedStudents = mutableSetOf<String>()
        val acceptedBySample = MutableList<StudentScore?>(samples.size) { null }
        candidates.forEach { (sampleIndex, score) ->
            if (claimedStudents.add(score.studentId)) {
                acceptedBySample[sampleIndex] = score
            }
        }
        return RecognitionPlan(rankedBySample, acceptedBySample)
    }

    private data class RegisteredEmbedding(
        val studentId: String,
        val studentName: String,
        val embedding: FloatArray
    )

    private data class StudentProfileRow(
        val studentId: String,
        val name: String,
        val sex: String,
        val embeddingCount: Int,
        val photoEmbeddingId: Long?
    )

    private data class StudentScore(
        val studentId: String,
        val studentName: String,
        val similarity: Float,
        val referenceCount: Int,
        val hasReferenceSupport: Boolean
    )

    private data class RecognitionPlan(
        val rankedBySample: List<List<StudentScore>>,
        val acceptedBySample: List<StudentScore?>
    )
}
