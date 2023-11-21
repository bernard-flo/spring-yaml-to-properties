package com.github.bernard_flo.spring.yamltoproperties

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default

class Args(parser: ArgParser) {

    val inputDir by parser.storing("directory of input").default(".")
    val outputDir by parser.storing("directory of output").default(".")
    val envDir by parser.storing("directory of env").default(null)

}
