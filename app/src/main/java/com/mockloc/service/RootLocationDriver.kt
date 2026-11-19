package com.mockloc.service

interface RootLocationDriver {
    suspend fun startOrUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ): Result<Unit>

    suspend fun stop(): Result<Unit>
}

object NoOpRootLocationDriver : RootLocationDriver {
    override suspend fun startOrUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ): Result<Unit> = Result.success(Unit)

    override suspend fun stop(): Result<Unit> = Result.success(Unit)
}
