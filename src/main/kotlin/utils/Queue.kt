package com.dallaslabs.utils

import com.dallaslabs.services.LogService
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * A queue for processing tasks with concurrency control
 */
class Queue(private val vertx: Vertx, private val concurrency: Int, logService: LogService) {

    private val taskQueue = ConcurrentLinkedQueue<Task<*>>()
    private val activeCount = AtomicInteger(0)
    private val size = AtomicInteger(0)
    private var closed = false

    init {
        logger.info { "Initializing queue with concurrency: $concurrency" }
    }

    /**
     * Adds a task to the queue
     *
     * @param task The task to execute
     * @return Future with the result of the task
     */
    fun <T> add(task: suspend () -> T): Future<T> {
        if (closed) {
            return Future.failedFuture("Queue is closed")
        }

        val promise = Promise.promise<T>()
        val queueTask = Task(task, promise)

        taskQueue.add(queueTask)
        size.incrementAndGet()

        // Try to process next task
        processNext()

        return promise.future()
    }

    /**
     * Gets the current size of the queue
     */
    fun size(): Int = size.get()

    /**
     * Gets the current number of active tasks
     */
    fun activeCount(): Int = activeCount.get()

    /**
     * Checks if the queue is paused
     */
    fun isPaused(): Boolean {
        return paused
    }

    /**
     * Closes the queue
     */
    fun close() {
        logger.info { "Closing queue" }
        closed = true
    }

    /**
     * Updates processNext method to check for paused state
     * This should replace your existing processNext() implementation
     */
    private fun processNext() {
        // Check if queue is paused
        if (paused) {
            return
        }

        // Check if we can process more tasks
        if (activeCount.get() >= concurrency) {
            return
        }

        // Get the next task
        val task = taskQueue.poll() ?: return

        // Update counters
        activeCount.incrementAndGet()

        // Execute the task
        vertx.executeBlocking<Any>({ promise ->
            try {
                runBlocking {
                    val result = task.execute()
                    promise.complete(result)
                }
            } catch (e: Exception) {
                promise.fail(e)
            }
        }, { res ->
            // Task completed, update counters
            activeCount.decrementAndGet()
            size.decrementAndGet()

            // Try to process next task
            processNext()
        })
    }

    /**
     * Whether the queue is paused
     */
    private var paused = false

    /**
     * Pauses the queue
     */
    fun pause() {
        if (!paused) {
            logger.info { "Pausing queue" }
            paused = true
        }
    }

    /**
     * Resumes the queue
     */
    fun resume() {
        if (paused) {
            logger.info { "Resuming queue" }
            paused = false

            // Try to process tasks that might have been added while paused
            processNext()
        }
    }

    private class Task<T>(
        private val task: suspend () -> T,
        private val promise: Promise<T>
    ) {
        suspend fun execute(): Any? {
            return try {
                val result = task()
                promise.complete(result)
                result
            } catch (e: Exception) {
                promise.fail(e)
                null
            }
        }
    }
}
