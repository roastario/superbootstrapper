package net.corda.bootstrapper.nodes

import com.typesafe.config.ConfigValue
import java.io.File

open class FoundNode(val configFile: File, val credentials: ConfigValue, val name: String = configFile.parentFile.name.toLowerCase())