package scalariform.commandline

import scalariform.formatter.preferences._
import java.io.File
import scala.io.Source
import scalariform.formatter.ScalaFormatter
import scalariform.utils.Utils.writeText

object Main {

  def main(args: Array[String]) {

    val parser = new CommandLineOptionParser
    val arguments = args.toList map parser.getArgument

    if (arguments contains Help) {
      println("Usage: scalariform [options] [files...]")
      println()
      println("Options:")
      println("  --help, -h       Show help")
      println("  --inPlace, -i    Replace the input file(s) in place with a formatted version.")
      println("  --test, -t       Check the input(s) to see if they are correctly formatted, return a non-zero error code if not.")
      println("  --verbose -v     Verbose output")
      println()
      println("Preferences:")
      for (key ← AllPreferences.preferencesByKey.keySet.toList.sorted) {
        def handlePreference[T](preference: PreferenceDescriptor[T]) {
          preference.preferenceType match {
            case BooleanPreference ⇒
              val filler = " " * (30 - key.length)
              println("  [+|-]" + key + filler + "Enable/disable " + preference.description)
            case IntegerPreference(min, max) ⇒
              val filler = " " * (28 - key.length)
              println("  " + key + "=[" + min + "-" + max + "]" + filler + "Set " + preference.description)
          }
        }
        handlePreference(AllPreferences.preferencesByKey(key))
      }
      println()
      println("Examples:")
      println(" scalariform +spaceBeforeColon -indentSpaces=2 --inPlace foo.scala")
      println(" find . -name '*.scala' | xargs scalariform +rewriteArrowSymbols --verbose --test")
      System.exit(0)
    }

    var errors: List[String] = Nil

    for (BadOption(s) ← arguments)
      errors ::= "Unrecognised option: " + s

    val preferenceOptions = (for (p@PreferenceOption(_, _) ← arguments) yield p)

    for (PreferenceOption(key, _) ← preferenceOptions if !(AllPreferences.preferencesByKey contains key))
      errors ::= "Unrecognised preference: " + key

    val preferences = if (errors.isEmpty)
      preferenceOptions.foldLeft(FormattingPreferences()) {
        case (preferences, PreferenceOption(key, valueString)) ⇒
          val descriptor = AllPreferences.preferencesByKey(key)
          def processDescriptor[T](descriptor: PreferenceDescriptor[T]) = {
            descriptor.preferenceType.parseValue(valueString) match {
              case Right(value) ⇒ preferences.setPreference(descriptor, value)
              case Left(error) ⇒
                errors ::= "Could not parse value for preference " + key + ", " + error
                preferences
            }
          }
          processDescriptor(descriptor)
      }
    else
      FormattingPreferences()

    var files: List[File] = Nil
    for (FileName(fileName) ← arguments) {
      val file = new File(fileName)
      if (!file.exists)
        errors ::= "No such file " + file
      if (file.isDirectory)
        errors ::= "Cannot format a directory (" + file + ")"
      files ::= file
    }
    files = files.reverse

    val test = arguments contains Test
    val inPlace = arguments contains InPlace
    val verbose = arguments contains Verbose

    if (inPlace && test)
      errors ::= "Incompatible arguments --test and --inPlace"

    if (inPlace && files.isEmpty)
      errors ::= "Need to provide at least one file to modify in place"

    if (!inPlace && !test && files.size > 1)
      errors ::= "Cannot have more than one input file unless using --test or --inPlace"

    if (verbose && !inPlace && !test)
      errors ::= "Will not be verbose unless using --test or --inPlace"

    if (!errors.isEmpty) {
      errors.reverse foreach System.err.println
      System.exit(1)
    }

    def log(s: String) = if (verbose) println(s)

    log("Formatting with preferences: " + preferences.preferencesMap.mkString(", "))

    if (test) {
      var allFormattedCorrectly = true
      def checkSource(source: Source) = ScalaFormatter.formatAsEdits(source.mkString, preferences).isEmpty
      if (files.isEmpty) {
        val formattedCorrectly = checkSource(Source.fromInputStream(System.in))
        allFormattedCorrectly &= formattedCorrectly
      } else
        for (file ← files) {
          val formattedCorrectly = checkSource(Source.fromFile(file))
          log("Checking " + file + " -- " + (if (formattedCorrectly) "[OK]" else "[FAILED]"))
          allFormattedCorrectly &= formattedCorrectly
        }
      System.exit(if (allFormattedCorrectly) 0 else 1)
    } else {
      if (files.isEmpty) {
        val original = Source.fromInputStream(System.in) mkString ""
        val formatted = ScalaFormatter.format(original, preferences)
        print(formatted)
      } else
        for (file ← files) {
          val original = Source.fromFile(file) mkString ""
          val formatted = ScalaFormatter.format(original, preferences)
          if (inPlace)
            if (formatted == original)
              log(file + " is already correctly formatted.")
            else {
              log("Formatting " + file)
              writeText(file, formatted)
            }
          else
            print(formatted)
        }
    }
  }

}
