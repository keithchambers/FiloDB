package filodb.cli

import com.github.lalyos.jfiglet.FigletFont
import org.apache.spark.sql.Row
import org.apache.spark.{SparkContext, SparkConf}
import org.jboss.aesh.console.helper.InterruptHook
import org.jboss.aesh.console.settings.SettingsBuilder
import org.jboss.aesh.console.{AeshConsoleCallback, Console, ConsoleOperation, Prompt}
import org.jboss.aesh.edit.actions.Action
import org.jboss.aesh.terminal._
import java.io.{IOException, PrintStream, PrintWriter}
import scala.collection.JavaConversions._
import filodb.spark.client._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import scala.language.postfixOps
import com.typesafe.config.ConfigFactory

object CliMain {

  var PROMPT: Array[Char] = "filo>".toCharArray
  var resultColor: TerminalColor = new TerminalColor(Color.WHITE, Color.DEFAULT)
  var successColor: TerminalColor = new TerminalColor(Color.GREEN, Color.DEFAULT)
  var errorColor: TerminalColor = new TerminalColor(Color.RED, Color.DEFAULT)

  // scalastyle:off
  @throws(classOf[IOException])
  def main(args: Array[String]): Unit = {
    val asciiArt = FigletFont.convertOneLine("FiloDB")
    println(asciiArt)
    val builder: SettingsBuilder = new SettingsBuilder().ansi(true)
    builder.logging(true).logfile(System.getProperty("user.dir") + System.getProperty("file.separator") + "filo.log")
    builder.interruptHook(SimpleInterruptHook())
    builder.persistHistory(true)
    builder.parseOperators(false)
    val console: Console = new Console(builder.create)
    val consoleCallback: ConsoleCallback = new ConsoleCallback(console)
    console.setConsoleCallback(consoleCallback)
    val chars = PROMPT.map { c =>
      new TerminalCharacter(c,
        new TerminalColor(Color.YELLOW, Color.DEFAULT, Color.Intensity.NORMAL),
        CharacterType.ITALIC)
    }.toList
    val config = ConfigFactory.load()
    val settings = config.getConfig("spark")
    val prompt: Prompt = new Prompt(chars)
    val conf = new SparkConf(false)
      .setMaster(settings.getString("master"))
      .setAppName(settings.getString("appName"))
      .set("spark.filodb.cassandra.hosts", settings.getString("hosts"))
      .set("spark.filodb.cassandra.port", settings.getString("port"))
      .set("spark.filodb.cassandra.keyspace", settings.getString("keyspace"))
      .setJars(Seq(settings.getString("jar")))
    val sc = new SparkContext(conf)
    FiloInterpreter.init(sc)
    console.start()
    console.setPrompt(prompt)
  }

  class ConsoleCallback(console: Console) extends AeshConsoleCallback {

    def execute(output: ConsoleOperation): Int = {
      val result = try {
        val printStream: PrintStream = console.getShell.out
        val printWriter: PrintWriter = new PrintWriter(printStream)
        output.getBuffer.toLowerCase match {
          case "quit" | "exit" | "reset" =>
            console.getShell.out.println()
            console.stop()
            FiloInterpreter.stop()
            System.exit(0)
          case "clear" => console.clear()
          case _ =>
            val start: Long = System.currentTimeMillis
            try {
              // do some operation
              // Call Filo Interpreter
              val result = FiloInterpreter.interpret(output.getBuffer)
              val df = Await.result(result,10 seconds)
              if(df.count() == 1 && df.columns.mkString(",") == "Filo-status") {
                  if(df.collect().head.mkString(",") == "1") {
                    printWriter.println(getSuccessString("Successful operation"))
                  }
                  if(df.collect().head.mkString(",") == "0") {
                       throw new Exception("Unsuccessful operation")
                    }
                }
              else {
                printWriter.println(getSuccessString(FiloInterpreter.showString(20, df)))
              }
              val end: Long = System.currentTimeMillis
              printWriter.println(getNormalString("Query took " + (start - end) + " millis"))
            } catch {
              case e: Exception => printWriter.println(getErrorString(e.getMessage))
            } finally {
              printWriter.flush()
              printStream.flush()
            }
        }
        0
      } catch {
        case ioe: IOException =>
          console.getShell.out.println(getErrorString(ioe.getMessage))
          -1
      }
      result
    }

  }

  private def getSuccessString(str: String): String =
    new TerminalString(str, successColor).toString

  private def getErrorString(str: String): String =
    new TerminalString(str, errorColor).toString

  private def getNormalString(str: String): String =
    new TerminalString(str, resultColor).toString

  case class SimpleInterruptHook() extends InterruptHook() {

    def handleInterrupt(console: Console, action: Action): Unit = {
      if (action eq Action.INTERRUPT) {
        console.getShell.out.println("^C")
        console.clearBufferAndDisplayPrompt()
      }
      else if (action eq Action.IGNOREEOF) {
        console.getShell.out.println("Use \"exit\" to leave the shell.")
        console.clearBufferAndDisplayPrompt()
      }
      else {
        console.getShell.out.println()
        console.stop()
      }
    }
  }

  // scalastyle:on
}
