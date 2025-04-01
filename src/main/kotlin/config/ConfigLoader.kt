package com.dallaslabs.config

import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ConfigLoader(private val vertx: Vertx) {

    /**
     * Loads configuration from a YAML file
     *
     * @param path Path to the configuration file
     * @return Future with the loaded configuration
     */
    fun loadConfig(path: String): Future<JsonObject> {
        logger.info { "Loading configuration from $path" }

        // Configure the YAML file store
        val fileStore = ConfigStoreOptions()
            .setType("file")
            .setFormat("yaml")
            .setConfig(JsonObject().put("path", path))

        // Create a config retriever
        val options = ConfigRetrieverOptions()
            .addStore(fileStore)
            .setScanPeriod(5000) // Scan every 5 seconds for changes

        val retriever = ConfigRetriever.create(vertx, options)

        // Listen for config changes
        retriever.listen {
            logger.info { "Configuration changed" }
            // You could broadcast the configuration change to components that need it
        }

        // Load the configuration
        return retriever.config
            .onSuccess {
                logger.info { "Configuration loaded successfully" }
            }
            .onFailure { error ->
                logger.error(error) { "Failed to load configuration" }
            }
    }
}
