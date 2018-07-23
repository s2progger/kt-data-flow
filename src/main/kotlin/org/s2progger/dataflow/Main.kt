package org.s2progger.dataflow

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.salomonbrys.kotson.*
import com.google.gson.Gson
import mu.KotlinLogging
import org.s2progger.dataflow.config.PathHelper
import org.s2progger.dataflow.config.PipelineConfiguration
import java.io.FileReader

class Main : CliktCommand(name = "data-flow", help = "Export database tables") {
    private val config: String by option(help = "Specify a path to the config file - default is to look for pipeline-config.json in the same directory the application in run from", envvar = "DF_CONFIG_FILE").default("pipeline-config.json")

    private val logger = KotlinLogging.logger {}

    override fun run() {
        val gson = Gson()

        try {
            val pipelineConfig = gson.fromJson<PipelineConfiguration>(FileReader(config))

            if (pipelineConfig.global?.pathSupplement != null) {
                PathHelper.appendToPath(pipelineConfig.global.pathSupplement)
            }

            DatabaseCopy(pipelineConfig).copyDatabase()

            logger.info("All done")
        } catch (e: Throwable){
            logger.error(e.toString())
        }
    }
}


fun main(args: Array<String>) = Main().main(args)
