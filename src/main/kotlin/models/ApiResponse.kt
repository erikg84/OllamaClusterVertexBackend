package com.dallaslabs.models

/**
 * Generic API response
 */
data class ApiResponse<T>(
    val status: String,
    val message: String? = null,
    val data: T? = null
) {
    companion object {
        /**
         * Creates a success response
         */
        fun <T> success(data: T? = null, message: String? = null): ApiResponse<T> {
            return ApiResponse(
                status = "ok",
                message = message,
                data = data
            )
        }

        /**
         * Creates an error response
         */
        fun <T> error(message: String, data: T? = null): ApiResponse<T> {
            return ApiResponse(
                status = "error",
                message = message,
                data = data
            )
        }
    }
}

/**
 * Represents the status of the queue
 */
data class QueueStatus(
    val size: Int,
    val pending: Int = 0,
    val isPaused: Boolean = false
)
