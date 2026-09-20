package com.example.networkrisktester.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: HistoryDao) {
    fun observeRecent(): Flow<List<HistoryEntity>> = dao.observeRecent()

    suspend fun save(report: DetectionReport) {
        val primary = report.primary
        dao.insert(
            HistoryEntity(
                timestamp = report.timestamp,
                ip = primary?.ip,
                country = primary?.country,
                city = primary?.city,
                isp = primary?.isp,
                asn = primary?.asn,
                score = report.score.score,
                grade = report.score.grade,
                reportText = report.toText()
            )
        )
        dao.trimToTwenty()
    }
}
