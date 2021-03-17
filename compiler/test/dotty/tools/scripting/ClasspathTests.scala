package dotty
package tools
package scripting

import java.io.File
import java.nio.file.{Files, Paths, Path}

import org.junit.Test

import vulpix.TestConfiguration

import scala.sys.process._
import dotty.tools.dotc.config.Properties._

/** Runs all tests contained in `compiler/test-resources/scripting/` */
class ClasspathTests:
  extension (str: String) def dropExtension =
    str.reverse.dropWhile(_ != '.').drop(1).reverse

  extension(f: File) def absPath =
    f.getAbsolutePath.replace('\\','/')

  // only interested in classpath test scripts
  def testFiles = scripts("/scripting").filter { _.getName.matches("classpath.*[.]sc") }

  /*
   * Call test scripts
   */
  @Test def scriptingJarTest =
    for scriptFile <- testFiles do
      val relpath = scriptFile.toPath.relpath.norm
      printf("===> test script name [%s]\n",relpath)
      printf("%s\n",relpath)
      val bashExe = which("bash")
      printf("bash is [%s]\n",bashExe)
      
      // ask [dist/bin/scalac] to echo generated command line so we can verify some things
      val cmd = Array(bashExe,"-c",s"SCALAC_ECHO_TEST=1 dist/target/pack/bin/scala -classpath '*/lib' $relpath")
      cmd.foreach { printf("cmd[%s]\n",_) }
      val javaCommandLine = exec(cmd:_*).mkString(" ").split(" ").filter { _.trim.nonEmpty }
      val args = scala.collection.mutable.Queue(javaCommandLine:_*)
      args.dequeueWhile( _ != "dotty.tools.scripting.Main")

      def consumeNext = args.dequeue()

      // assert that we found "dotty.tools.scripting.Main"
      assert(consumeNext == "dotty.tools.scripting.Main")
      val mainArgs = args.copyToArray(Array.ofDim[String](args.length))

      // display command line starting with "dotty.tools.scripting.Main"
      args.foreach { line =>
        printf("%s\n",line)
      }

      // expecting -classpath next
      assert(consumeNext.replaceAll("'","") == "-classpath")
      
      // 2nd arg to scripting.Main is 'lib/*', with semicolon added if Windows jdk
    
      // PR #10761: verify that [dist/bin/scala] -classpath processing adds $psep to wildcard if Windows
      val classpathValue = consumeNext
      assert( !isWin || classpathValue.contains(psep) )
    
      // expecting -script next
      assert(consumeNext.replaceAll("'","") == "-script")

      // PR #10761: verify that Windows jdk did not expand single wildcard classpath to multiple file paths
      if javaCommandLine.last != relpath then
        printf("last: %s\nrelp: %s\n",javaCommandLine.last,relpath)
        assert(javaCommandLine.last == relpath,s"unexpected args passed to scripting.Main")


lazy val cwd = Paths.get(".").toAbsolutePath

import scala.jdk.CollectionConverters._
lazy val env:Map[String,String] = System.getenv.asScala.toMap

def exec(cmd: String *): Seq[String] = Process(cmd).lazyLines_!.toList

def which(str:String) =
  var out = ""
  path.find { entry =>
    val it = Paths.get(entry).toAbsolutePath
    it.toFile.isDirectory && {
      var testpath = s"$it/$str".norm
      val test = Paths.get(testpath)
      if test.toFile.exists then
        out = testpath
        true
      else
        val test = Paths.get(s"$it/$str.exe".norm)
        if test.toFile.exists then
          out = testpath
          true
        else
          false
      }
    }

  out

def bashExe = which("bash")
def path = envOrElse("PATH","").split(psep).toList
def psep = sys.props("path.separator")

extension(p:Path)
  def relpath: Path = cwd.relativize(p)
  def norm: String = p.toString.replace('\\','/')

extension(path: String)
  // Normalize path separator, convert relative path to absolute
  def norm: String =
    path.replace('\\', '/') match {
      case s if s.secondChar == ":" => s // .drop(2) // path without drive letter
      case s if s.startsWith("./") => s.drop(2)
      case s => s
    }

  def parent: String = norm.replaceAll("/[^/]*$","")

  // convert to absolute path relative to cwd.
  def absPath: String = norm match
    case str if str.isAbsolute => norm
    case _ => Paths.get(userDir,norm).toString.norm

  def isDir: Boolean = Files.isDirectory(Paths.get(path))

  def toUrl: String = Paths.get(absPath).toUri.toURL.toString

  // Treat norm paths with a leading '/' as absolute.
  // Windows java.io.File#isAbsolute treats them as relative.
  def isAbsolute = path.norm.startsWith("/") || (isWin && path.secondChar == ":")
  def secondChar: String = path.take(2).drop(1).mkString("")

